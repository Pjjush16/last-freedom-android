<?php
/**
 * Easy AI · PHP 后端 v2
 * ------------------------------------------------------
 * - OpenAI 兼容接口代理（SSE 流式 + 多线路故障转移 / strict 单线路）
 * - 配置管理（API Key 只保存在服务器端，前端永不可见）
 * - 对话持久化 + 文件夹分组 + 全文搜索
 * - 分享链接（只读快照）
 * - 提示词库
 *
 * 依赖：PHP >= 7.4 + curl 扩展。无框架、无数据库，放入网站目录即用。
 */

error_reporting(E_ALL);
ini_set('display_errors', '0');

define('DATA_DIR', __DIR__ . '/data');
define('CONFIG_FILE', DATA_DIR . '/config.json');
define('CHATS_DIR', DATA_DIR . '/chats');
define('FOLDERS_FILE', DATA_DIR . '/folders.json');
define('SHARES_DIR', DATA_DIR . '/shares');
define('PROMPTS_FILE', DATA_DIR . '/prompts.json');

if (!is_dir(DATA_DIR))  @mkdir(DATA_DIR, 0777, true);
if (!is_dir(CHATS_DIR)) @mkdir(CHATS_DIR, 0777, true);
if (!is_dir(SHARES_DIR)) @mkdir(SHARES_DIR, 0777, true);

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

// 大小写不敏感的包含判断
function str_has($haystack, $needle) {
    if ($needle === '') return true;
    if (function_exists('mb_stripos')) return mb_stripos($haystack, $needle) !== false;
    return stripos($haystack, $needle) !== false;
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

function clean_id($s) {
    return preg_replace('/[^A-Za-z0-9_\-]/', '', (string)$s);
}

/* ------------------------------------------------------------------ */
/* 免 Key 网页搜索（DuckDuckGo HTML 为主，Bing 兜底）+ 网页正文抓取     */
/* ------------------------------------------------------------------ */

function http_get($url, $timeout = 10, $post = null) {
    $ch = curl_init();
    curl_setopt_array($ch, [
        CURLOPT_URL            => $url,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS      => 4,
        CURLOPT_TIMEOUT        => $timeout,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => 0,
        CURLOPT_ENCODING       => '',
        CURLOPT_USERAGENT      => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        CURLOPT_HTTPHEADER     => ['Accept-Language: zh-CN,zh;q=0.9,en;q=0.8'],
    ]);
    if ($post !== null) {
        curl_setopt($ch, CURLOPT_POST, true);
        curl_setopt($ch, CURLOPT_POSTFIELDS, is_array($post) ? http_build_query($post) : $post);
    }
    $body = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    return ($code >= 200 && $code < 400 && $body !== false) ? $body : '';
}

function html_to_text($html) {
    $html = preg_replace('/<(script|style|noscript|svg|head|form|nav|footer|header)[\s\S]*?<\/\1>/i', ' ', $html);
    $html = preg_replace('/<br\s*\/?>/i', "\n", $html);
    $html = preg_replace('/<\/(p|div|li|h\d|tr|td|section|article)>/i', "\n", $html);
    $text = strip_tags($html);
    $text = html_entity_decode($text, ENT_QUOTES, 'UTF-8');
    $text = preg_replace('/[ \t\r]+/', ' ', $text);
    $text = preg_replace('/\n\s*\n+/', "\n", $text);
    return trim($text);
}

function ddg_search($q, $max) {
    $html = http_get('https://html.duckduckgo.com/html/', 12, ['q' => $q, 'kl' => 'cn-zh']);
    if ($html === '') return [];
    $out = [];
    preg_match_all('/<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/i', $html, $hits, PREG_SET_ORDER);
    preg_match_all('/<a[^>]*class="result__snippet"[^>]*>([\s\S]*?)<\/a>/i', $html, $snips);
    foreach ($hits as $i => $hit) {
        if (count($out) >= $max) break;
        $url = html_entity_decode($hit[1], ENT_QUOTES);
        if (strpos($url, 'uddg=') !== false) {
            $qs = [];
            parse_str((string)parse_url($url, PHP_URL_QUERY), $qs);
            if (!empty($qs['uddg'])) $url = $qs['uddg'];
        }
        $title = trim(html_entity_decode(strip_tags($hit[2]), ENT_QUOTES));
        $snippet = isset($snips[1][$i]) ? trim(html_entity_decode(strip_tags($snips[1][$i]), ENT_QUOTES)) : '';
        if ($url !== '' && $title !== '' && strpos($url, 'http') === 0) {
            $out[] = ['title' => $title, 'url' => $url, 'snippet' => $snippet];
        }
    }
    return $out;
}

function bing_search($q, $max) {
    $html = http_get('https://www.bing.com/search?q=' . urlencode($q) . '&setlang=zh-CN&count=' . $max, 12);
    if ($html === '') return [];
    $out = [];
    preg_match_all('/<li[^>]*class="b_algo"[\s\S]*?<\/li>/i', $html, $blocks);
    foreach ($blocks[0] as $blk) {
        if (count($out) >= $max) break;
        if (!preg_match('/<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/i', $blk, $m)) continue;
        $url = html_entity_decode($m[1], ENT_QUOTES);
        $title = trim(html_entity_decode(strip_tags($m[2]), ENT_QUOTES));
        $snippet = '';
        if (preg_match('/<p[^>]*>([\s\S]*?)<\/p>/i', $blk, $pm)) {
            $snippet = trim(html_entity_decode(strip_tags($pm[1]), ENT_QUOTES));
        }
        if ($url !== '' && $title !== '') $out[] = ['title' => $title, 'url' => $url, 'snippet' => $snippet];
    }
    return $out;
}

function web_search($q, $max = 5) {
    $results = ddg_search($q, $max);
    if (count($results) < 2) {
        $b = bing_search($q, $max);
        if (count($b) > count($results)) $results = $b;
    }
    return $results;
}

// 并发抓取搜索结果正文（curl_multi），每页截断为纯文本
function fetch_pages($urls, $maxChars = 1200) {
    $urls = array_slice(array_values($urls), 0, 4);
    if (!$urls) return [];
    $mh = curl_multi_init();
    $handles = [];
    foreach ($urls as $u) {
        $ch = curl_init();
        curl_setopt_array($ch, [
            CURLOPT_URL            => $u,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS      => 3,
            CURLOPT_TIMEOUT        => 9,
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => 0,
            CURLOPT_ENCODING       => '',
            CURLOPT_MAXFILESIZE    => 2000000,
            CURLOPT_USERAGENT      => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        ]);
        curl_multi_add_handle($mh, $ch);
        $handles[$u] = $ch;
    }
    $running = null;
    do {
        curl_multi_exec($mh, $running);
        if ($running) curl_multi_select($mh, 0.2);
    } while ($running > 0);
    $out = [];
    foreach ($handles as $u => $ch) {
        $body = curl_multi_getcontent($ch);
        curl_multi_remove_handle($mh, $ch);
        curl_close($ch);
        if (!$body) continue;
        $text = html_to_text($body);
        if ($text !== '') $out[$u] = str_cut($text, $maxChars);
    }
    curl_multi_close($mh);
    return $out;
}

function read_chat_file($id) {
    if ($id === '') return null;
    $c = jread(CHATS_DIR . '/' . $id . '.json', null);
    return is_array($c) ? $c : null;
}

function chat_meta($c) {
    return [
        'id'         => (string)($c['id'] ?? ''),
        'title'      => (string)($c['title'] ?? '新对话'),
        'folder'     => (string)($c['folder'] ?? ''),
        'updated_at' => (int)($c['updated_at'] ?? 0),
    ];
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
        json_out([
            'name'   => $cfg['name'],
            'prompt' => $cfg['prompt'],
            'providers' => array_map(function ($p) {
                return ['url' => $p['url'], 'key' => mask_key($p['key']), 'model' => $p['model']];
            }, $cfg['providers']),
        ]);
    }
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

/* ---------------- 会话列表（支持搜索 q） ---------------- */
case 'chats':
    $q = trim((string)($_GET['q'] ?? ''));
    $list = [];
    foreach (glob(CHATS_DIR . '/*.json') ?: [] as $f) {
        $c = jread($f, null);
        if (!is_array($c) || empty($c['id'])) continue;
        if ($q !== '') {
            $hit = str_has((string)($c['title'] ?? ''), $q);
            if (!$hit) {
                foreach (($c['messages'] ?? []) as $m) {
                    if (str_has((string)($m['content'] ?? ''), $q)) { $hit = true; break; }
                }
            }
            if (!$hit) continue;
        }
        $list[] = chat_meta($c);
    }
    usort($list, function ($a, $b) { return $b['updated_at'] <=> $a['updated_at']; });
    json_out(['chats' => $list]);
    break;

/* ---------------- 单个会话 读/存 ---------------- */
case 'chat':
    if ($method === 'GET') {
        $id = clean_id($_GET['id'] ?? '');
        if ($id === '') json_err('缺少 id');
        $c = read_chat_file($id);
        if (!$c) json_err('会话不存在', 404);
        json_out($c);
    }

    // POST 保存
    $id = clean_id($body['id'] ?? '');
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
    $old = read_chat_file($id);
    // 文件夹：显式传了就用新值，否则保留原值
    $folder = $old ? (string)($old['folder'] ?? '') : '';
    if (array_key_exists('folder', $body)) $folder = clean_id($body['folder']);
    $chat = [
        'id'         => $id,
        'title'      => str_cut($title, 60),
        'folder'     => $folder,
        'created_at' => is_array($old) && !empty($old['created_at']) ? (int)$old['created_at'] : time(),
        'updated_at' => time(),
        'messages'   => $clean,
    ];
    jwrite(CHATS_DIR . '/' . $id . '.json', $chat);
    json_out(['ok' => true, 'id' => $id]);
    break;

case 'chat_delete':
    $id = clean_id($body['id'] ?? '');
    if ($id !== '') @unlink(CHATS_DIR . '/' . $id . '.json');
    json_out(['ok' => true]);
    break;

/* ---------------- 移动会话到文件夹 ---------------- */
case 'chat_move':
    $id = clean_id($body['id'] ?? '');
    $c = read_chat_file($id);
    if (!$c) json_err('会话不存在', 404);
    $c['folder'] = clean_id($body['folder'] ?? '');
    jwrite(CHATS_DIR . '/' . $id . '.json', $c);
    json_out(['ok' => true]);
    break;

/* ---------------- 文件夹 ---------------- */
case 'folders':
    json_out(['folders' => jread(FOLDERS_FILE, [])]);
    break;

case 'folder_save':
    $name = trim((string)($body['name'] ?? ''));
    if ($name === '') json_err('文件夹名称不能为空');
    $folders = jread(FOLDERS_FILE, []);
    $id = clean_id($body['id'] ?? '');
    if ($id !== '') {
        foreach ($folders as &$f) {
            if (($f['id'] ?? '') === $id) { $f['name'] = str_cut($name, 30); break; }
        }
        unset($f);
    } else {
        $id = 'f_' . date('YmdHis') . substr(md5(uniqid('', true)), 0, 6);
        $folders[] = ['id' => $id, 'name' => str_cut($name, 30)];
    }
    jwrite(FOLDERS_FILE, $folders);
    json_out(['ok' => true, 'id' => $id]);
    break;

case 'folder_delete':
    $id = clean_id($body['id'] ?? '');
    $folders = array_values(array_filter(jread(FOLDERS_FILE, []), function ($f) use ($id) {
        return ($f['id'] ?? '') !== $id;
    }));
    jwrite(FOLDERS_FILE, $folders);
    // 该文件夹下的会话改为未分组
    foreach (glob(CHATS_DIR . '/*.json') ?: [] as $f) {
        $c = jread($f, null);
        if (is_array($c) && ($c['folder'] ?? '') === $id) {
            $c['folder'] = '';
            jwrite($f, $c);
        }
    }
    json_out(['ok' => true]);
    break;

/* ---------------- 分享 ---------------- */
case 'share':
    if ($method === 'GET') {
        $token = clean_id($_GET['token'] ?? '');
        if ($token === '') json_err('缺少 token');
        $s = jread(SHARES_DIR . '/' . $token . '.json', null);
        if (!is_array($s)) json_err('分享不存在或已删除', 404);
        json_out($s);
    }
    // POST：为某个会话创建只读快照
    $id = clean_id($body['id'] ?? '');
    $c = read_chat_file($id);
    if (!$c) json_err('会话不存在', 404);
    $token = substr(str_replace(['+', '/', '='], '', base64_encode(random_bytes(12))), 0, 12);
    jwrite(SHARES_DIR . '/' . $token . '.json', [
        'token'      => $token,
        'title'      => (string)($c['title'] ?? '分享'),
        'messages'   => $c['messages'] ?? [],
        'created_at' => time(),
    ]);
    json_out(['ok' => true, 'token' => $token]);
    break;

case 'share_delete':
    $token = clean_id($body['token'] ?? '');
    if ($token !== '') @unlink(SHARES_DIR . '/' . $token . '.json');
    json_out(['ok' => true]);
    break;

/* ---------------- 提示词库 ---------------- */
case 'prompts':
    json_out(['prompts' => jread(PROMPTS_FILE, [])]);
    break;

case 'prompt_save':
    $title = trim((string)($body['title'] ?? ''));
    $content = trim((string)($body['content'] ?? ''));
    if ($title === '' || $content === '') json_err('标题和内容不能为空');
    $prompts = jread(PROMPTS_FILE, []);
    $id = clean_id($body['id'] ?? '');
    if ($id !== '') {
        $found = false;
        foreach ($prompts as &$p) {
            if (($p['id'] ?? '') === $id) {
                $p['title'] = str_cut($title, 50);
                $p['content'] = $content;
                $p['updated_at'] = time();
                $found = true;
                break;
            }
        }
        unset($p);
        if (!$found) $id = '';
    }
    if ($id === '') {
        $id = 'p_' . date('YmdHis') . substr(md5(uniqid('', true)), 0, 6);
        $prompts[] = ['id' => $id, 'title' => str_cut($title, 50), 'content' => $content, 'updated_at' => time()];
    }
    jwrite(PROMPTS_FILE, $prompts);
    json_out(['ok' => true, 'id' => $id]);
    break;

case 'prompt_delete':
    $id = clean_id($body['id'] ?? '');
    $prompts = array_values(array_filter(jread(PROMPTS_FILE, []), function ($p) use ($id) {
        return ($p['id'] ?? '') !== $id;
    }));
    jwrite(PROMPTS_FILE, $prompts);
    json_out(['ok' => true]);
    break;

/* ---------------- 独立搜索（调试用） ---------------- */
case 'websearch':
    $q = trim((string)($_GET['q'] ?? ''));
    if ($q === '') json_err('q 不能为空');
    json_out(['results' => web_search($q, 6)]);
    break;

/* ---------------- 流式生成 ---------------- */
case 'generate':
    if (!function_exists('curl_init')) json_err('服务器缺少 PHP curl 扩展', 500);

    $messages = isset($body['messages']) && is_array($body['messages']) ? $body['messages'] : [];
    if (!$messages) json_err('messages 不能为空');

    $prefer = isset($body['provider']) ? (int)$body['provider'] : -1;
    $strict = !empty($body['strict']);
    $cfg = load_config();

    $valid = [];
    foreach ($cfg['providers'] as $i => $p) {
        if ($p['url'] !== '' && $p['key'] !== '' && $p['model'] !== '') $valid[] = $i;
    }
    if (!$valid) json_err('尚未配置可用的 API 线路，请先在设置中填写', 400);

    // 组装尝试顺序
    if ($prefer >= 0) {
        if (!in_array($prefer, $valid, true)) json_err('指定线路不可用', 400);
        $order = $strict ? [$prefer] : array_merge([$prefer], array_values(array_diff($valid, [$prefer])));
    } else {
        $order = $valid;
    }

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

    /* ---- 联网搜索：优先 Tools（模型按需自主搜索），不支持 tools 的线路回退预注入 ---- */
    $web = !empty($body['web']);
    $webTools = $web ? [[
        'type' => 'function',
        'function' => [
            'name' => 'web_search',
            'description' => '搜索互联网获取实时信息（最新新闻、产品发布、价格、赛事、天气、时效性事实等）。当用户问题需要最新信息或事实核查时必须调用；query 使用提炼后的简洁关键词。',
            'parameters' => [
                'type' => 'object',
                'properties' => [
                    'query' => ['type' => 'string', 'description' => '搜索关键词，简洁准确']
                ],
                'required' => ['query']
            ]
        ]
    ]] : null;

    $conv = $full;          // 工作消息（含 tool 历史）
    $done = false;
    $toolRounds = 0;
    $allSources = [];
    $toolUnsupported = false;

    while (!$done) {
        $useTools = ($webTools !== null && $toolRounds < 3) ? $webTools : null;
        $okAny = false;
        $toolCalls = null;
        foreach ($order as $idx) {
            $p = $cfg['providers'][$idx];
            $errText = '';
            $ok = stream_from_provider($p, $conv, $sse, $useTools, $toolCalls, $errText);
            if ($ok) { $okAny = true; break; }
            if (connection_aborted()) break 2;
            if ($errText !== '' && preg_match('/tools?|function|工具|参数/i', $errText)) $toolUnsupported = true;
        }

        if (!$okAny) {
            // 全部线路失败：若因不支持 tools → 回退为预注入方式重试
            if ($useTools !== null && $toolUnsupported) {
                $webTools = null;
                $toolUnsupported = false;
                inject_web_context($conv, $sse, $allSources);
                continue;
            }
            break;
        }

        if (is_array($toolCalls) && $toolCalls) {
            // 模型要求搜索 → 执行工具并把结果喂回去继续对话
            $asToolCalls = [];
            foreach ($toolCalls as $tc) {
                $asToolCalls[] = [
                    'id' => $tc['id'],
                    'type' => 'function',
                    'function' => ['name' => $tc['name'], 'arguments' => $tc['arguments']]
                ];
            }
            $conv[] = ['role' => 'assistant', 'content' => '', 'tool_calls' => $asToolCalls];
            foreach ($toolCalls as $tc) {
                $args = json_decode((string)$tc['arguments'], true);
                if (!is_array($args)) $args = [];
                $result = ($tc['name'] === 'web_search')
                    ? exec_web_search_tool($args, $allSources)
                    : '未知工具';
                $conv[] = ['role' => 'tool', 'tool_call_id' => $tc['id'], 'content' => $result];
            }
            $sse(['web_results' => $allSources]);
            $toolRounds++;
            continue;
        }

        $done = true;
    }

    if (!$done && !headers_sent()) {
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
        'actions' => ['config', 'chats', 'chat', 'chat_delete', 'chat_move', 'folders', 'folder_save', 'folder_delete', 'share', 'share_delete', 'prompts', 'prompt_save', 'prompt_delete', 'generate'],
    ]);
}

exit;

/* ------------------------------------------------------------------ */
/* 联网搜索工具执行 + 预注入兜底                                       */
/* ------------------------------------------------------------------ */

// 执行 web_search 工具调用：搜索 + 抓正文，返回给模型的文本资料
function exec_web_search_tool($args, &$allSources) {
    $q = str_cut(trim((string)($args['query'] ?? '')), 80);
    if ($q === '') return '没有提供搜索关键词。';
    $results = web_search($q, 5);
    $top = array_slice($results, 0, 4);
    if (!$top) return '未搜索到与「' . $q . '」相关的网页内容。';
    foreach ($top as $r) $allSources[] = ['title' => $r['title'], 'url' => $r['url']];
    $pages = fetch_pages(array_map(function ($r) { return $r['url']; }, $top));
    $text = '检索时间：' . date('Y-m-d H:i') . '，共 ' . count($top) . " 条结果：\n\n";
    foreach ($top as $i => $r) {
        $text .= '[' . ($i + 1) . '] ' . $r['title'] . "\nURL: " . $r['url'] . "\n";
        $pageText = isset($pages[$r['url']]) ? $pages[$r['url']] : '';
        $text .= ($pageText !== '' ? $pageText : $r['snippet']) . "\n\n";
    }
    return $text;
}

// 兜底方案：预搜索并注入最后一条用户消息（用于不支持 Tools 的线路）
function inject_web_context(&$conv, $sse, &$allSources) {
    $lastUser = '';
    $lastIdx = -1;
    foreach ($conv as $k => $m) {
        if (($m['role'] ?? '') === 'user') { $lastUser = (string)($m['content'] ?? ''); $lastIdx = $k; }
    }
    $q = str_cut(trim((string)preg_replace('/\s+/', ' ', $lastUser)), 80);
    if ($q === '' || $lastIdx < 0) return;
    $results = web_search($q, 5);
    $top = array_slice($results, 0, 4);
    if (!$top) return;
    foreach ($top as $r) $allSources[] = ['title' => $r['title'], 'url' => $r['url']];
    $sse(['web_results' => $allSources]);
    $pages = fetch_pages(array_map(function ($r) { return $r['url']; }, $top));
    $ctx = "【互联网搜索结果】检索时间：" . date('Y-m-d H:i') . "。请优先依据下列资料回答用户问题，引用时用 [n] 标注来源序号；若资料与问题无关则忽略资料、直接回答。\n\n";
    foreach ($top as $i => $r) {
        $ctx .= '[' . ($i + 1) . '] ' . $r['title'] . "\n链接: " . $r['url'] . "\n";
        $pageText = isset($pages[$r['url']]) ? $pages[$r['url']] : '';
        $ctx .= ($pageText !== '' ? $pageText : $r['snippet']) . "\n\n";
    }
    $conv[$lastIdx]['content'] = $ctx . "【用户问题】" . $conv[$lastIdx]['content'];
}

/* ------------------------------------------------------------------ */
/* 单个线路的流式请求（支持 Tools 多轮）。成功返回 true                 */
/* $toolCallsOut：若本轮是纯 tool_calls（无正文），返回解析出的调用列表  */
/* ------------------------------------------------------------------ */
function stream_from_provider($provider, $messages, $sse, $tools = null, &$toolCallsOut = null, &$errOut = '') {
    $toolCallsOut = null;
    $errOut = '';
    $payload = [
        'model'       => $provider['model'],
        'messages'    => $messages,
        'stream'      => true,
        'temperature' => 0.6,
        'max_tokens'  => 4096,
    ];
    if ($tools !== null) {
        $payload['tools'] = $tools;
        $payload['tool_choice'] = 'auto';
    }

    $isSSE    = null;
    $started  = false;
    $jsonBuf  = '';
    $errBuf   = '';
    $httpCode = 0;
    $lineBuf  = '';
    $acc      = [];       // index => ['id','name','arguments'] 累积的 tool_calls 分片
    $hasContent = false;

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
        CURLOPT_WRITEFUNCTION => function ($ch, $chunk) use (&$isSSE, &$started, &$jsonBuf, &$errBuf, &$httpCode, &$lineBuf, &$acc, &$hasContent, $sse, $provider) {
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
                $jsonBuf .= $chunk;
                return strlen($chunk);
            }
            // 逐行转发（过滤上游 [DONE]，最终由后端统一补发），同时解析 tool_calls 分片
            $lineBuf .= $chunk;
            while (($pos = strpos($lineBuf, "\n")) !== false) {
                $rawLine = substr($lineBuf, 0, $pos);
                $lineBuf = substr($lineBuf, $pos + 1);
                $line = trim($rawLine);
                if ($line === '') continue;
                if ($line === 'data: [DONE]' || $line === 'data:[DONE]') continue;
                echo $rawLine . "\n";
                if (strpos($line, 'data:') !== 0) continue;
                $pl = trim(substr($line, 5));
                if ($pl === '') continue;
                $ev = json_decode($pl, true);
                if (!is_array($ev) || empty($ev['choices']) || !is_array($ev['choices'])) continue;
                $ch0 = $ev['choices'][0];
                $delta = (isset($ch0['delta']) && is_array($ch0['delta'])) ? $ch0['delta'] : [];
                if (!empty($delta['content'])) $hasContent = true;
                if (!empty($delta['tool_calls']) && is_array($delta['tool_calls'])) {
                    foreach ($delta['tool_calls'] as $tc) {
                        $i = isset($tc['index']) ? (int)$tc['index'] : 0;
                        if (!isset($acc[$i])) $acc[$i] = ['id' => '', 'name' => '', 'arguments' => ''];
                        if (!empty($tc['id'])) $acc[$i]['id'] = (string)$tc['id'];
                        if (!empty($tc['function']['name'])) $acc[$i]['name'] .= (string)$tc['function']['name'];
                        if (!empty($tc['function']['arguments'])) $acc[$i]['arguments'] .= (string)$tc['function']['arguments'];
                    }
                }
            }
            @flush();
            return strlen($chunk);
        },
    ]);

    curl_exec($ch);
    $httpCode = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);

    if (!$started) { $errOut = $errBuf; return false; }
    if ($httpCode >= 400) { $errOut = $errBuf; return false; }

    // 非流式上游
    if ($isSSE === false && $jsonBuf !== '') {
        $data = json_decode($jsonBuf, true);
        if (is_array($data) && isset($data['choices'][0]['message'])) {
            $msg = $data['choices'][0]['message'];
            if (!empty($msg['tool_calls']) && is_array($msg['tool_calls'])) {
                $tcs = [];
                foreach ($msg['tool_calls'] as $i => $tc) {
                    $tcs[] = [
                        'id'        => (string)($tc['id'] ?? ('call_' . $i)),
                        'name'      => (string)(isset($tc['function']['name']) ? $tc['function']['name'] : 'web_search'),
                        'arguments' => (string)(isset($tc['function']['arguments']) ? $tc['function']['arguments'] : '{}'),
                    ];
                }
                $toolCallsOut = $tcs;
                return true;
            }
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

    // 流结束：纯 tool_calls 轮（没有正文）→ 返回解析出的调用
    if (!$hasContent && $acc) {
        $tcs = [];
        foreach ($acc as $i => $t) {
            if ($t['name'] === '' && $t['arguments'] === '') continue;
            $tcs[] = [
                'id'        => $t['id'] !== '' ? $t['id'] : ('call_' . $i),
                'name'      => $t['name'] !== '' ? $t['name'] : 'web_search',
                'arguments' => $t['arguments'] !== '' ? $t['arguments'] : '{}',
            ];
        }
        if ($tcs) { $toolCallsOut = $tcs; return true; }
    }

    return true;
}
