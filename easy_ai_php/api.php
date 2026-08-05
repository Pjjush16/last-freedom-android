<?php
/**
 * Easy AI · PHP 后端
 * ------------------------------------------------------
 * - OpenAI 兼容接口代理（SSE 流式输出 + 多线路自动故障转移）
 * - 配置管理（API Key 只保存在服务器端，前端永不可见）
 * - 对话持久化（JSON 文件存储，自动创建 data/ 目录）
 *
 * 依赖：PHP >= 7.4 + curl 扩展。无任何框架依赖，直接放入网站目录即可。
 *
 * 接口一览（全部走 ?action=）：
 *   GET  ?action=config        读取配置（密钥打码）
 *   POST ?action=config        保存配置（密钥留空或原样 = 保持不变）
 *   GET  ?action=chats         会话列表
 *   GET  ?action=chat&id=xx    读取单个会话
 *   POST ?action=chat          保存会话 {id?, title, messages}
 *   POST ?action=chat_delete   删除会话 {id}
 *   POST ?action=generate      流式生成（SSE）{messages, provider?}
 */

error_reporting(E_ALL);
ini_set('display_errors', '0');

define('DATA_DIR', __DIR__ . '/data');
define('CONFIG_FILE', DATA_DIR . '/config.json');
define('CHATS_DIR', DATA_DIR . '/chats');

if (!is_dir(DATA_DIR))  @mkdir(DATA_DIR, 0777, true);
if (!is_dir(CHATS_DIR)) @mkdir(CHATS_DIR, 0777, true);

/* ------------------------------------------------------------------ */
/* 内置输出规则（画图 + 作曲），生成时自动追加到系统提示词后面          */
/* ------------------------------------------------------------------ */
define('AUTO_RULES', <<<'RULES'

---
## 输出规则

### 🎨 图片
用 【photo】 和 【/photo】 包裹。
预设图形：!heart cx cy size color | !star cx cy size color | !smile cx cy size | !sun cx cy size | !cloud cx cy size | !flower cx cy size color | !tree cx cy size | !house cx cy size
颜色：red blue green yellow purple orange pink white black gray
也可直接写SVG代码。

### 🎵 音乐
用 ```audio 代码块。每行：音名 时长(秒) [力度]
音名：CDEFGAB+#b+八度 R=休止 +=和弦
力度：loud normal soft（可选，默认随机人性化）
多音轨：!track 乐器名
127种乐器：piano violin cello harp flute sax trumpet trombone drum snare hihat timpani 等
和弦：!chord Cmaj 2 | 速度：!bpm 120

示例：
```audio
!track violin
C4 0.5 loud
D4 0.5 soft
E4 0.5
C4 1
```
RULES
);

define('DEFAULT_NAME', 'Easy AI');
define('DEFAULT_PROMPT', '你是一个乐于助人、富有创造力的 AI 助手。请用中文回答。');

/* ------------------------------------------------------------------ */
/* 基础工具                                                            */
/* ------------------------------------------------------------------ */

function jread($file, $default) {
    if (!is_file($file)) return $default;
    $raw = @file_get_contents($file);
    if ($raw === false || $raw === '') return $default;
    $d = json_decode($raw, true);
    return is_array($d) ? $d : $default;
}

function jwrite($file, $data) {
    $tmp = $file . '.' . uniqid('', true) . '.tmp';
    @file_put_contents($tmp, json_encode($data, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
    @rename($tmp, $file);
}

function json_out($data, $code = 200) {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function json_err($msg, $code = 400) {
    json_out(['error' => $msg], $code);
}

function mask_key($k) {
    if ($k === '') return '';
    $n = strlen($k);
    $head = substr($k, 0, min(3, $n));
    return $head . str_repeat('*', max(4, min(8, $n - 3)));
}

// 多字节安全截断（不强依赖 mbstring 扩展）
function str_cut($s, $len) {
    if (function_exists('mb_substr')) return mb_substr($s, 0, $len);
    $out = ''; $count = 0; $i = 0; $n = strlen($s);
    while ($i < $n && $count < $len) {
        $byte = ord($s[$i]);
        if ($byte < 0x80)       $step = 1;
        elseif ($byte >= 0xF0)  $step = 4;
        elseif ($byte >= 0xE0)  $step = 3;
        elseif ($byte >= 0xC0)  $step = 2;
        else                    $step = 1;
        if ($i + $step > $n) break;
        $out .= substr($s, $i, $step);
        $i += $step; $count++;
    }
    return $out;
}

function default_config() {
    return [
        'name'   => DEFAULT_NAME,
        'prompt' => DEFAULT_PROMPT,
        'providers' => [
            ['url' => '', 'key' => '', 'model' => ''],
            ['url' => '', 'key' => '', 'model' => ''],
            ['url' => '', 'key' => '', 'model' => ''],
        ],
    ];
}

function load_config() {
    $cfg = array_replace_recursive(default_config(), jread(CONFIG_FILE, []));
    // 规范 providers 结构
    $ps = [];
    for ($i = 0; $i < 3; $i++) {
        $p = isset($cfg['providers'][$i]) && is_array($cfg['providers'][$i]) ? $cfg['providers'][$i] : [];
        $ps[] = [
            'url'   => trim((string)($p['url'] ?? '')),
            'key'   => trim((string)($p['key'] ?? '')),
            'model' => trim((string)($p['model'] ?? '')),
        ];
    }
    $cfg['providers'] = $ps;
    return $cfg;
}

/* ------------------------------------------------------------------ */
/* 路由                                                                */
/* ------------------------------------------------------------------ */

$action = isset($_GET['action']) ? (string)$_GET['action'] : '';
$method = $_SERVER['REQUEST_METHOD'];
$body = [];
if ($method === 'POST' || $method === 'PUT' || $method === 'DELETE') {
    $raw = file_get_contents('php://input');
    if ($raw !== false && $raw !== '') {
        $decoded = json_decode($raw, true);
        if (is_array($decoded)) $body = $decoded;
    }
}

switch ($action) {

/* ---------------- 配置 ---------------- */
case 'config':
    if ($method === 'GET') {
        $cfg = load_config();
        $out = [
            'name'   => $cfg['name'],
            'prompt' => $cfg['prompt'],
            'providers' => array_map(function ($p) {
                return ['url' => $p['url'], 'key' => mask_key($p['key']), 'model' => $p['model']];
            }, $cfg['providers']),
        ];
        json_out($out);
    }

    // POST：保存。密钥字段为空、或与打码值一致 → 视为不修改
    $old = load_config();
    $new = default_config();
    $new['name']   = trim((string)($body['name'] ?? '')) ?: DEFAULT_NAME;
    $new['prompt'] = trim((string)($body['prompt'] ?? '')) ?: DEFAULT_PROMPT;
    for ($i = 0; $i < 3; $i++) {
        $p = isset($body['providers'][$i]) && is_array($body['providers'][$i]) ? $body['providers'][$i] : [];
        $key = trim((string)($p['key'] ?? ''));
        if ($key === '' || $key === mask_key($old['providers'][$i]['key'])) {
            $key = $old['providers'][$i]['key'];
        }
        $new['providers'][$i] = [
            'url'   => trim((string)($p['url'] ?? '')),
            'key'   => $key,
            'model' => trim((string)($p['model'] ?? '')),
        ];
    }
    jwrite(CONFIG_FILE, $new);
    json_out(['ok' => true]);
    break;

/* ---------------- 会话列表 ---------------- */
case 'chats':
    $list = [];
    foreach (glob(CHATS_DIR . '/*.json') ?: [] as $f) {
        $c = jread($f, null);
        if (!is_array($c) || empty($c['id'])) continue;
        $list[] = [
            'id'         => (string)$c['id'],
            'title'      => (string)($c['title'] ?? '新对话'),
            'updated_at' => (int)($c['updated_at'] ?? 0),
        ];
    }
    usort($list, function ($a, $b) { return $b['updated_at'] <=> $a['updated_at']; });
    json_out(['chats' => $list]);
    break;

/* ---------------- 单个会话 读/存/删 ---------------- */
case 'chat':
    if ($method === 'GET') {
        $id = preg_replace('/[^A-Za-z0-9_\-]/', '', (string)($_GET['id'] ?? ''));
        if ($id === '') json_err('缺少 id');
        $c = jread(CHATS_DIR . '/' . $id . '.json', null);
        if (!is_array($c)) json_err('会话不存在', 404);
        json_out($c);
    }

    if ($method === 'DELETE' || $action === 'chat_delete') {
        $id = preg_replace('/[^A-Za-z0-9_\-]/', '', (string)($body['id'] ?? ($_GET['id'] ?? '')));
        if ($id !== '') @unlink(CHATS_DIR . '/' . $id . '.json');
        json_out(['ok' => true]);
    }

    // POST 保存
    $id = preg_replace('/[^A-Za-z0-9_\-]/', '', (string)($body['id'] ?? ''));
    if ($id === '') $id = date('YmdHis') . '_' . substr(md5(uniqid('', true)), 0, 8);
    $title = trim((string)($body['title'] ?? ''));
    if ($title === '') $title = '新对话';
    $msgs = isset($body['messages']) && is_array($body['messages']) ? $body['messages'] : [];
    $clean = [];
    foreach ($msgs as $m) {
        if (!is_array($m)) continue;
        $role = (string)($m['role'] ?? '');
        if (!in_array($role, ['user', 'assistant'], true)) continue;
        $item = ['role' => $role, 'content' => (string)($m['content'] ?? '')];
        if (!empty($m['thinking'])) $item['thinking'] = (string)$m['thinking'];
        $clean[] = $item;
    }
    $old = jread(CHATS_DIR . '/' . $id . '.json', null);
    $chat = [
        'id'         => $id,
        'title'      => str_cut($title, 60),
        'created_at' => is_array($old) && !empty($old['created_at']) ? (int)$old['created_at'] : time(),
        'updated_at' => time(),
        'messages'   => $clean,
    ];
    jwrite(CHATS_DIR . '/' . $id . '.json', $chat);
    json_out(['ok' => true, 'id' => $id]);
    break;

case 'chat_delete':
    $id = preg_replace('/[^A-Za-z0-9_\-]/', '', (string)($body['id'] ?? ''));
    if ($id !== '') @unlink(CHATS_DIR . '/' . $id . '.json');
    json_out(['ok' => true]);
    break;

/* ---------------- 流式生成 ---------------- */
case 'generate':
    if (!function_exists('curl_init')) json_err('服务器缺少 PHP curl 扩展', 500);

    $messages = isset($body['messages']) && is_array($body['messages']) ? $body['messages'] : [];
    if (!$messages) json_err('messages 不能为空');

    $prefer = isset($body['provider']) ? (int)$body['provider'] : -1;
    $cfg = load_config();

    // 有效线路
    $valid = [];
    foreach ($cfg['providers'] as $i => $p) {
        if ($p['url'] !== '' && $p['key'] !== '' && $p['model'] !== '') $valid[] = $i;
    }
    if (!$valid) json_err('尚未配置可用的 API 线路，请先在设置中填写', 400);

    // 优先线路排前面，其余按顺序做故障转移
    $order = $valid;
    if ($prefer >= 0 && in_array($prefer, $valid, true)) {
        $order = array_values(array_diff($valid, [$prefer]));
        array_unshift($order, $prefer);
    }

    // 组装完整 messages：系统提示词（含输出规则）+ 历史
    $sys = $cfg['prompt'] . AUTO_RULES;
    $full = [['role' => 'system', 'content' => $sys]];
    foreach ($messages as $m) {
        if (!is_array($m)) continue;
        $role = (string)($m['role'] ?? '');
        if (!in_array($role, ['user', 'assistant'], true)) continue;
        $content = (string)($m['content'] ?? '');
        if ($content === '') continue;
        $full[] = ['role' => $role, 'content' => $content];
    }

    // SSE 响应头
    header('Content-Type: text/event-stream; charset=utf-8');
    header('Cache-Control: no-cache, no-transform');
    header('Connection: keep-alive');
    header('X-Accel-Buffering: no');
    if (function_exists('apache_setenv')) @apache_setenv('no-gzip', '1');
    @ini_set('output_buffering', 'off');
    @ini_set('zlib.output_compression', 'off');
    while (ob_get_level() > 0) @ob_end_flush();
    @ignore_user_abort(true);
    @set_time_limit(0);

    $sse = function ($arr) {
        echo 'data: ' . json_encode($arr, JSON_UNESCAPED_UNICODE) . "\n\n";
        @flush();
    };

    $done = false;
    foreach ($order as $idx) {
        $p = $cfg['providers'][$idx];
        $ok = stream_from_provider($p, $full, $sse);
        if ($ok) { $done = true; break; }
        // 该线路失败且尚未输出任何内容 → 继续尝试下一条线路
        if (connection_aborted()) break;
    }

    if (!$done && !headers_sent()) {
        // 走到这里说明一条都没成功（且一条都没输出过）
        $sse(['error' => '所有 API 线路均不可用，请检查配置或稍后再试']);
    }
    echo "data: [DONE]\n\n";
    @flush();
    break;

/* ---------------- 默认 ---------------- */
default:
    json_out([
        'name'    => 'Easy AI API',
        'ok'      => true,
        'actions' => ['config', 'chats', 'chat', 'chat_delete', 'generate'],
    ]);
}

exit;

/* ------------------------------------------------------------------ */
/* 单个线路的流式请求。成功输出过内容返回 true，否则 false              */
/* ------------------------------------------------------------------ */
function stream_from_provider($provider, $messages, $sse) {
    $payload = [
        'model'       => $provider['model'],
        'messages'    => $messages,
        'stream'      => true,
        'temperature' => 0.6,
        'max_tokens'  => 4096,
    ];

    $isSSE    = null;   // 上游是否真 SSE
    $started  = false;  // 是否已向浏览器吐出过正文
    $jsonBuf  = '';     // 上游若返回整块 JSON，先攒着
    $errBuf   = '';
    $httpCode = 0;

    $ch = curl_init();
    curl_setopt_array($ch, [
        CURLOPT_URL            => $provider['url'],
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => json_encode($payload, JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER     => [
            'Content-Type: application/json',
            'Authorization: Bearer ' . $provider['key'],
            'Accept: text/event-stream',
        ],
        CURLOPT_RETURNTRANSFER => false,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS      => 3,
        CURLOPT_CONNECTTIMEOUT => 15,
        CURLOPT_TIMEOUT        => 600,
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => 0,
        CURLOPT_HEADERFUNCTION => function ($ch, $header) use (&$isSSE) {
            if (stripos($header, 'content-type:') === 0) {
                $isSSE = (stripos($header, 'text/event-stream') !== false);
            }
            return strlen($header);
        },
        CURLOPT_WRITEFUNCTION => function ($ch, $chunk) use (&$isSSE, &$started, &$jsonBuf, &$errBuf, &$httpCode, $sse, $provider) {
            $httpCode = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
            if (connection_aborted()) return -1;

            if ($httpCode >= 400) {
                $errBuf .= $chunk;
                return strlen($chunk);
            }
            if (!$started) {
                $started = true;
                $sse(['meta' => true, 'model' => $provider['model']]);
            }
            if ($isSSE === false) {
                $jsonBuf .= $chunk;          // 非 SSE 上游：攒完整块再转成 SSE
            } else {
                echo $chunk;                 // SSE 上游：原样透传
                @flush();
            }
            return strlen($chunk);
        },
    ]);

    curl_exec($ch);
    $curlErr  = curl_error($ch);
    $httpCode = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);

    // 一条内容都没吐出去 → 判定本线路失败，交给下一条
    if (!$started) return false;

    if ($httpCode >= 400) return false;

    // 上游返回的是完整 JSON（非流式）→ 手动拆成 SSE 事件
    if ($isSSE === false && $jsonBuf !== '') {
        $data = json_decode($jsonBuf, true);
        if (is_array($data) && isset($data['choices'][0]['message'])) {
            $msg = $data['choices'][0]['message'];
            $delta = [];
            if (!empty($msg['reasoning_content'])) $delta['reasoning_content'] = (string)$msg['reasoning_content'];
            if (!empty($msg['content']))           $delta['content']           = (string)$msg['content'];
            if ($delta) $sse(['choices' => [['delta' => $delta, 'index' => 0]]]);
        } else {
            $reason = is_array($data) && isset($data['error']['message']) ? $data['error']['message'] : '上游返回格式异常';
            $sse(['error' => '线路返回错误：' . $reason]);
            return false;
        }
    }

    return true;
}
