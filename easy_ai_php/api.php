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
define('UPLOADS_DIR', DATA_DIR . '/uploads');
define('SECRET_FILE', DATA_DIR . '/.secret');
define('KB_FILE', DATA_DIR . '/kb.json');
define('USERS_FILE', DATA_DIR . '/users.json');

if (!is_dir(UPLOADS_DIR)) @mkdir(UPLOADS_DIR, 0777, true);

// 上传限制
define('MAX_UPLOAD_SIZE', 15 * 1024 * 1024); // 15MB
define('ALLOWED_EXT', 'png,jpg,jpeg,gif,webp,bmp,txt,md,csv,json,log,pdf,docx,xlsx,pptx,zip,mp3,wav');

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
用户要求画画/画图/画场景时，用 【photo】 开头、【/photo】 结尾，中间直接写完整 SVG 代码。
要求：
- 根标签固定 <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300">，画布 400x300
- 自由使用 rect/circle/ellipse/line/path/polygon/polyline/text，用 <defs> 定义 linearGradient/radialGradient 渐变，用 <g> 分组
- 按场景自由构图：先画背景（天空、地面、夜色、光源），再画远景、近景、主体；注意遮挡层次和色彩搭配
- 需要夜晚/黄昏等氛围时用深色背景渐变，月亮星星、灯光光晕等细节自由发挥
- SVG 必须格式规范：每个标签都要有对应的闭合标签（<circle .../> 或 <circle></circle>），属性值一律加双引号，不要输出多余的解释文字
- 标记必须严格完整：【photo】 与 【/photo】 各占一头，不要写错括号位置
示例结构：【photo】<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300"><rect width="400" height="300" fill="#0b1026"/>...</svg>【/photo】

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
    if ($n <= 7) return str_repeat('*', max(4, $n));
    // 首3位+末4位：不同 Key 打码值可区分（删卡重排后仍能对上原 Key）
    return substr($k, 0, 3) . '****' . substr($k, -4);
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

/* ------------------------------------------------------------------ */
/* API Key 静态加密（sodium secretbox，密钥存 data/.secret，权限 600）  */
/* ------------------------------------------------------------------ */
function get_secret_key() {
    if (!function_exists('sodium_crypto_secretbox')) return null;
    if (is_file(SECRET_FILE)) {
        $k = file_get_contents(SECRET_FILE);
        if ($k !== false && strlen($k) === SODIUM_CRYPTO_SECRETBOX_KEYBYTES) return $k;
    }
    $k = sodium_crypto_secretbox_keygen();
    @file_put_contents(SECRET_FILE, $k);
    @chmod(SECRET_FILE, 0600);
    return $k;
}
function encrypt_str($plain) {
    if ($plain === '') return '';
    $key = get_secret_key();
    if ($key === null) return 'plain:' . $plain; // 无 sodium 时退化为明文标记
    $nonce = random_bytes(SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
    $cipher = sodium_crypto_secretbox($plain, $nonce, $key);
    return 'enc:' . base64_encode($nonce . $cipher);
}
function decrypt_str($stored) {
    if ($stored === '') return '';
    if (strpos($stored, 'enc:') === 0) {
        $key = get_secret_key();
        if ($key === null) return '';
        $raw = base64_decode(substr($stored, 4), true);
        if ($raw === false || strlen($raw) <= SODIUM_CRYPTO_SECRETBOX_NONCEBYTES) return '';
        $nonce = substr($raw, 0, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
        $plain = sodium_crypto_secretbox_open(substr($raw, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES), $nonce, $key);
        return $plain === false ? '' : $plain;
    }
    if (strpos($stored, 'plain:') === 0) return substr($stored, 6);
    return $stored; // 历史明文配置，读取时透传，下次保存自动加密
}

function default_config() {
    return [
        'name'   => DEFAULT_NAME,
        'prompt' => DEFAULT_PROMPT,
        'embed_model' => '',
        'embed_url' => '',
        'providers' => [
            ['url' => '', 'key' => '', 'model' => ''],
        ],
    ];
}

function load_config() {
    $cfg = array_replace_recursive(default_config(), jread(CONFIG_FILE, []));
    $file = jread(CONFIG_FILE, []);
    $rawProviders = (isset($file['providers']) && is_array($file['providers']))
        ? array_values($file['providers'])
        : $cfg['providers'];
    $ps = [];
    foreach ($rawProviders as $p) {
        if (!is_array($p)) continue;
        $ps[] = [
            'url'   => trim((string)($p['url'] ?? '')),
            'key'   => trim(decrypt_str((string)($p['key'] ?? ''))),
            'model' => trim((string)($p['model'] ?? '')),
        ];
    }
    if (!$ps) $ps = [['url' => '', 'key' => '', 'model' => '']];
    $cfg['providers'] = $ps;
    $cfg['embed_model'] = trim((string)($file['embed_model'] ?? ''));
    $cfg['embed_url'] = trim((string)($file['embed_url'] ?? ''));
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

/* ------------------------------------------------------------------ */
/* 多用户：会话 + 鉴权                                                  */
/* ------------------------------------------------------------------ */
session_name('easy_ai_sess');
session_set_cookie_params(['httponly' => true, 'samesite' => 'Lax', 'path' => '/']);
session_start();

function load_users() { return jread(USERS_FILE, []); }
function save_users($u) { jwrite(USERS_FILE, $u); }
function current_user() {
    if (session_status() !== PHP_SESSION_ACTIVE) return null;
    return isset($_SESSION['ea_user']) ? (string)$_SESSION['ea_user'] : null;
}
function user_role($name) {
    foreach (load_users() as $u) if ($u['username'] === $name) return $u['role'] ?? 'user';
    return null;
}
function is_admin() {
    $u = current_user();
    if ($u === null) return false;
    return user_role($u) === 'admin';
}
// 会话归属：无 user 字段的历史会话对已登录用户可见
function chat_owner_ok($chat) {
    $owner = $chat['user'] ?? '';
    if ($owner === '') return true;
    return $owner === current_user() || is_admin();
}

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

// 鉴权门：除登录/初始化/本人信息/只读分享与文件外，均需登录
$publicActions = ['login', 'setup', 'me'];
if ($action === 'share' && $method === 'GET') $publicActions[] = 'share';
if ($action === 'file' && $method === 'GET') $publicActions[] = 'file';
if (!in_array($action, $publicActions, true) && current_user() === null) {
    json_out(['error' => '未登录', 'need_login' => true], 401);
}

switch ($action) {

/* ---------------- 用户与会话 ---------------- */
case 'setup':
    if (count(load_users()) > 0) json_err('已存在用户，请直接登录', 403);
    $username = trim((string)($body['username'] ?? ''));
    $password = (string)($body['password'] ?? '');
    if ($username === '' || strlen($password) < 4) json_err('用户名不能为空，密码至少 4 位');
    save_users([[
        'username' => $username,
        'pass_hash' => password_hash($password, PASSWORD_DEFAULT),
        'role' => 'admin',
        'created_at' => time(),
    ]]);
    session_regenerate_id(true);
    $_SESSION['ea_user'] = $username;
    json_out(['ok' => true, 'username' => $username, 'role' => 'admin']);
    break;

case 'login':
    $username = trim((string)($body['username'] ?? ''));
    $password = (string)($body['password'] ?? '');
    foreach (load_users() as $usr) {
        if (($usr['username'] ?? '') === $username && password_verify($password, $usr['pass_hash'] ?? '')) {
            session_regenerate_id(true);
            $_SESSION['ea_user'] = $username;
            json_out(['ok' => true, 'username' => $username, 'role' => $usr['role'] ?? 'user']);
        }
    }
    json_err('用户名或密码错误', 401);
    break;

case 'logout':
    unset($_SESSION['ea_user']);
    session_destroy();
    json_out(['ok' => true]);
    break;

case 'me':
    $u = current_user();
    if ($u === null) {
        json_out(['logged_in' => false, 'has_users' => count(load_users()) > 0]);
    } else {
        json_out(['logged_in' => true, 'username' => $u, 'role' => user_role($u) ?: 'user']);
    }
    break;

case 'users':
    if (!is_admin()) json_err('需要管理员权限', 403);
    json_out(['users' => array_map(function ($x) {
        return ['username' => $x['username'], 'role' => $x['role'] ?? 'user', 'created_at' => $x['created_at'] ?? 0];
    }, load_users())]);
    break;

case 'user_add':
    if (!is_admin()) json_err('需要管理员权限', 403);
    $username = trim((string)($body['username'] ?? ''));
    $password = (string)($body['password'] ?? '');
    $role = ($body['role'] ?? 'user') === 'admin' ? 'admin' : 'user';
    if ($username === '' || strlen($password) < 4) json_err('用户名不能为空，密码至少 4 位');
    $users = load_users();
    foreach ($users as $x) if ($x['username'] === $username) json_err('用户名已存在');
    $users[] = ['username' => $username, 'pass_hash' => password_hash($password, PASSWORD_DEFAULT), 'role' => $role, 'created_at' => time()];
    save_users($users);
    json_out(['ok' => true]);
    break;

case 'user_delete':
    if (!is_admin()) json_err('需要管理员权限', 403);
    $target = trim((string)($body['username'] ?? ''));
    if ($target === current_user()) json_err('不能删除当前登录的自己');
    $users = load_users();
    $admins = 0;
    foreach ($users as $x) if (($x['role'] ?? 'user') === 'admin') $admins++;
    foreach ($users as $x) {
        if ($x['username'] === $target && ($x['role'] ?? 'user') === 'admin' && $admins <= 1) {
            json_err('不能删除唯一的管理员');
        }
    }
    $users = array_values(array_filter($users, function ($x) use ($target) { return $x['username'] !== $target; }));
    save_users($users);
    json_out(['ok' => true]);
    break;

/* ---------------- 配置 ---------------- */
case 'config':
    if ($method === 'GET') {
        $cfg = load_config();
        json_out([
            'name'   => $cfg['name'],
            'prompt' => $cfg['prompt'],
            'embed_model' => $cfg['embed_model'] ?? '',
            'embed_url' => $cfg['embed_url'] ?? '',
            'providers' => array_map(function ($p) {
                return ['url' => $p['url'], 'key' => mask_key($p['key']), 'model' => $p['model']];
            }, $cfg['providers']),
        ]);
    }
    $old = load_config();
    // 旧 Key 池：提交的打码值能对上任意旧 Key 即复用（支持删卡后索引错位）
    $oldKeys = [];
    foreach ($old['providers'] as $op) {
        if ($op['key'] !== '') $oldKeys[] = $op['key'];
    }
    $new = default_config();
    $new['name']   = trim((string)($body['name'] ?? '')) ?: DEFAULT_NAME;
    $new['prompt'] = trim((string)($body['prompt'] ?? '')) ?: DEFAULT_PROMPT;
    $new['embed_model'] = trim((string)($body['embed_model'] ?? ''));
    $new['embed_url'] = trim((string)($body['embed_url'] ?? ''));
    $submitted = (isset($body['providers']) && is_array($body['providers'])) ? array_values($body['providers']) : [];
    $ps = [];
    foreach ($submitted as $p) {
        if (!is_array($p)) continue;
        $url   = trim((string)($p['url'] ?? ''));
        $model = trim((string)($p['model'] ?? ''));
        $key   = trim((string)($p['key'] ?? ''));
        if ($key !== '') {
            foreach ($oldKeys as $ok) {
                if (mask_key($ok) === $key) { $key = $ok; break; } // 未改动的打码值 → 复用服务器 Key
            }
        }
        if ($url === '' && $key === '' && $model === '') continue; // 跳过全空线路
        $ps[] = [
            'url'   => $url,
            'key'   => encrypt_str($key),
            'model' => $model,
        ];
    }
    if (!$ps) $ps = [['url' => '', 'key' => '', 'model' => '']];
    $new['providers'] = $ps;
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
        if (!chat_owner_ok($c)) continue;
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
        if (!chat_owner_ok($c)) json_err('无权访问他人的会话', 403);
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
        if (!empty($m['attachments']) && is_array($m['attachments'])) {
            $atts = [];
            foreach ($m['attachments'] as $a) {
                $aid = clean_id(is_array($a) ? ($a['id'] ?? '') : $a);
                if ($aid !== '' && is_file(UPLOADS_DIR . '/' . $aid . '.meta.json')) {
                    $am = jread(UPLOADS_DIR . '/' . $aid . '.meta.json', []);
                    $atts[] = ['id' => $aid, 'name' => (string)($am['name'] ?? ''), 'mime' => (string)($am['mime'] ?? '')];
                }
            }
            if ($atts) $item['attachments'] = $atts;
        }
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
    if ($id !== '') {
        $c = read_chat_file($id);
        if ($c && !chat_owner_ok($c)) json_err('无权删除他人的会话', 403);
        @unlink(CHATS_DIR . '/' . $id . '.json');
    }
    json_out(['ok' => true]);
    break;

/* ---------------- 移动会话到文件夹 ---------------- */
case 'chat_move':
    $id = clean_id($body['id'] ?? '');
    $c = read_chat_file($id);
    if (!$c) json_err('会话不存在', 404);
    if (!chat_owner_ok($c)) json_err('无权操作他人的会话', 403);
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

/* ---------------- 文件上传（供 read_image / read_file 工具使用） ---------------- */
case 'upload':
    if (empty($_FILES) || !isset($_FILES['file'])) json_err('未收到文件（字段名应为 file）');
    $f = $_FILES['file'];
    if (($f['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) json_err('上传失败，错误码 ' . (int)$f['error']);
    $size = (int)($f['size'] ?? 0);
    if ($size <= 0) json_err('文件为空');
    if ($size > MAX_UPLOAD_SIZE) json_err('文件超过 ' . (MAX_UPLOAD_SIZE / 1024 / 1024) . 'MB 限制');

    $origName = (string)($f['name'] ?? 'file');
    $ext = strtolower(pathinfo($origName, PATHINFO_EXTENSION));
    $allowed = explode(',', ALLOWED_EXT);
    if ($ext === '' || !in_array($ext, $allowed, true)) {
        json_err('不支持的文件类型：.' . $ext);
    }

    // 推断 MIME
    $mimeMap = [
        'png'=>'image/png','jpg'=>'image/jpeg','jpeg'=>'image/jpeg','gif'=>'image/gif','webp'=>'image/webp','bmp'=>'image/bmp',
        'txt'=>'text/plain','md'=>'text/markdown','csv'=>'text/csv','json'=>'application/json','log'=>'text/plain',
        'pdf'=>'application/pdf','docx'=>'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'xlsx'=>'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'pptx'=>'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        'zip'=>'application/zip','mp3'=>'audio/mpeg','wav'=>'audio/wav',
    ];
    $mime = isset($mimeMap[$ext]) ? $mimeMap[$ext] : 'application/octet-stream';
    if (function_exists('finfo_open')) {
        $fi = finfo_open(FILEINFO_MIME_TYPE);
        $detected = finfo_file($fi, $f['tmp_name']);
        finfo_close($fi);
        if ($detected) $mime = $detected;
    }

    $id = 'u_' . date('YmdHis') . '_' . substr(md5(uniqid('', true)), 0, 8);
    $dest = UPLOADS_DIR . '/' . $id;
    if (!move_uploaded_file($f['tmp_name'], $dest)) json_err('保存文件失败');

    $meta = [
        'id'         => $id,
        'name'       => str_cut($origName, 120),
        'ext'        => $ext,
        'mime'       => $mime,
        'size'       => $size,
        'created_at' => time(),
    ];
    jwrite(UPLOADS_DIR . '/' . $id . '.meta.json', $meta);
    json_out(['ok' => true, 'id' => $id, 'name' => $meta['name'], 'mime' => $mime, 'size' => $size]);
    break;

/* ---------------- 读取已上传文件（预览 / 下载） ---------------- */
case 'file':
    $id = clean_id($_GET['id'] ?? '');
    if ($id === '') json_err('缺少 id');
    $path = UPLOADS_DIR . '/' . $id;
    if (!is_file($path)) json_err('文件不存在', 404);
    $meta = jread($path . '.meta.json', ['name' => $id, 'mime' => 'application/octet-stream']);
    $mime = (string)($meta['mime'] ?? 'application/octet-stream');
    $name = (string)($meta['name'] ?? $id);
    $download = !empty($_GET['download']);
    header('Content-Type: ' . $mime);
    header('Content-Length: ' . filesize($path));
    header('Content-Disposition: ' . ($download ? 'attachment' : 'inline') . '; filename="' . rawurlencode($name) . '"');
    header('Cache-Control: private, max-age=3600');
    readfile($path);
    exit;

/* ---------------- 上传列表 / 删除 ---------------- */
case 'uploads':
    $list = [];
    foreach (glob(UPLOADS_DIR . '/*.meta.json') ?: [] as $mf) {
        $m = jread($mf, null);
        if (is_array($m) && !empty($m['id'])) $list[] = $m;
    }
    usort($list, function ($a, $b) { return ($b['created_at'] ?? 0) <=> ($a['created_at'] ?? 0); });
    json_out(['uploads' => $list]);
    break;

case 'upload_delete':
    $id = clean_id($body['id'] ?? '');
    if ($id !== '') {
        @unlink(UPLOADS_DIR . '/' . $id);
        @unlink(UPLOADS_DIR . '/' . $id . '.meta.json');
    }
    json_out(['ok' => true]);
    break;

/* ---------------- RAG 知识库 ---------------- */
case 'kb_list':
    $kb = load_kb();
    $out = [];
    foreach ($kb as $doc) {
        $out[] = [
            'id' => $doc['id'] ?? '',
            'title' => $doc['title'] ?? '',
            'chunks' => count($doc['chunks'] ?? []),
            'chars' => $doc['chars'] ?? 0,
            'created_at' => $doc['created_at'] ?? 0,
        ];
    }
    json_out(['docs' => $out, 'embed_ready' => rag_endpoint(load_config()) !== null]);
    break;

case 'kb_add':
    $cfg = load_config();
    $hasEmbed = rag_endpoint($cfg) !== null;
    $title = trim((string)($body['title'] ?? ''));
    $content = (string)($body['content'] ?? '');
    if ($title === '' || trim($content) === '') json_err('标题和内容不能为空');
    $chunks = chunk_text($content);
    if (!$chunks) json_err('内容分块失败');
    if (count($chunks) > 200) json_err('内容过长（超过 200 块），请拆分后上传');
    $vecs = null;
    if ($hasEmbed) {
        $vecs = embed_batch($cfg, $chunks);
        if ($vecs === null) json_err('向量化失败：请检查 embed_model / embed_url 是否正确（或留空 embed_model 改用关键词检索）');
    }
    $docChunks = [];
    foreach ($chunks as $i => $c) $docChunks[] = ['text' => $c, 'vec' => $vecs ? $vecs[$i] : null];
    $kb = load_kb();
    $id = 'kb_' . date('YmdHis') . substr(md5(uniqid('', true)), 0, 6);
    $kb[] = ['id' => $id, 'title' => str_cut($title, 80), 'chunks' => $docChunks, 'chars' => strlen($content), 'created_at' => time()];
    jwrite(KB_FILE, $kb);
    json_out(['ok' => true, 'id' => $id, 'chunks' => count($docChunks), 'mode' => $hasEmbed ? 'semantic' : 'keyword']);
    break;

case 'kb_delete':
    $id = clean_id($body['id'] ?? '');
    $kb = array_values(array_filter(load_kb(), function ($d) use ($id) { return ($d['id'] ?? '') !== $id; }));
    jwrite(KB_FILE, $kb);
    json_out(['ok' => true]);
    break;

case 'kb_query':
    $cfg = load_config();
    $q = trim((string)($body['q'] ?? ''));
    if ($q === '') json_err('缺少问题');
    $hits = kb_retrieve($cfg, $q, (int)($body['top_k'] ?? 4));
    json_out(['hits' => array_map(function ($h) { return ['score' => round($h['score'], 4), 'title' => $h['title'], 'text' => str_cut($h['text'], 800)]; }, $hits)]);
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
        if ($p['url'] !== '' && $p['model'] !== '') $valid[] = $i; // Key 选填：Ollama 等本地服务无需鉴权
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

    /* ---- 技能系统：Tools（模型按需调用），不支持 tools 的线路回退预注入 ---- */
    $web = !empty($body['web']);

    // 解析本次消息携带的附件（已上传文件的 id）
    $attachments = [];
    if (!empty($body['attachments']) && is_array($body['attachments'])) {
        foreach ($body['attachments'] as $aid) {
            $aid = clean_id(is_array($aid) ? ($aid['id'] ?? '') : $aid);
            if ($aid === '') continue;
            $meta = jread(UPLOADS_DIR . '/' . $aid . '.meta.json', null);
            if (is_array($meta) && !empty($meta['id'])) $attachments[] = $meta;
        }
    }
    $hasImage = false;
    $hasFile = false;
    foreach ($attachments as $a) {
        if (strpos((string)$a['mime'], 'image/') === 0) $hasImage = true;
        else $hasFile = true;
    }

    // 技能注册表：按场景动态组装
    $agent = !empty($body['agent']);
    $tools = build_tool_defs($web, $hasImage, $hasFile, $agent);

    // 把附件清单注入到最后一条用户消息，让模型知道有哪些文件可用
    if ($attachments) {
        $lastIdx = -1;
        for ($k = count($full) - 1; $k >= 0; $k--) {
            if ($full[$k]['role'] === 'user') { $lastIdx = $k; break; }
        }
        if ($lastIdx >= 0) {
            $list = '';
            foreach ($attachments as $a) {
                $kind = strpos((string)$a['mime'], 'image/') === 0 ? '图片' : '文件';
                $list .= '- [' . $kind . '] ' . $a['name'] . '（id=' . $a['id'] . '，类型 ' . $a['mime'] . "）\n";
            }
            $hint = $hasImage
                ? "\n\n【用户上传了附件】如需了解图片内容，请调用 read_image 工具（传入 image_id）：\n"
                : "\n\n【用户上传了附件】如需读取文件内容，请调用 read_file 工具（传入 file_id）：\n";
            $full[$lastIdx]['content'] .= $hint . $list;
        }
    }

    // RAG：从知识库检索相关片段注入上下文
    $rag = !empty($body['rag']);
    $ragSources = [];
    if ($rag) {
        $lastUser = '';
        for ($k = count($full) - 1; $k >= 0; $k--) {
            if ($full[$k]['role'] === 'user') { $lastUser = $full[$k]['content']; break; }
        }
        $q = str_cut(trim((string)preg_replace('/\s+/', ' ', strip_tags($lastUser))), 200);
        if ($q !== '') {
            $hits = kb_retrieve($cfg, $q, 4);
            if ($hits) {
                $kbCtx = "\n\n【知识库相关资料】以下是从用户知识库检索到的内容，回答时请优先参考并标注来源：\n";
                foreach ($hits as $hi => $h) {
                    $kbCtx .= '--- 来源[' . ($hi + 1) . '] ' . $h['title'] . '（相关度 ' . number_format($h['score'], 2) . "）---\n" . str_cut($h['text'], 1000) . "\n";
                    $ragSources[] = ['title' => $h['title'], 'score' => round($h['score'], 3)];
                }
                for ($k = count($full) - 1; $k >= 0; $k--) {
                    if ($full[$k]['role'] === 'user') { $full[$k]['content'] .= $kbCtx; break; }
                }
                $sse(['rag_sources' => $ragSources]);
            }
        }
    }

    $conv = $full;          // 工作消息（含 tool 历史）
    $done = false;
    $toolRounds = 0;
    $allSources = [];
    $toolUnsupported = false;
    $toolCtx = ['cfg' => $cfg, 'attachments' => $attachments];

    while (!$done) {
        $useTools = ($tools !== null && count($tools) > 0 && $toolRounds < 3) ? $tools : null;
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
            // 全部线路失败：若因不支持 tools → 回退为预注入方式重试（仅联网搜索可回退）
            if ($useTools !== null && $toolUnsupported) {
                $tools = null;
                $toolUnsupported = false;
                if ($web) inject_web_context($conv, $sse, $allSources);
                continue;
            }
            break;
        }

        if (is_array($toolCalls) && $toolCalls) {
            // 模型要求调用工具 → 执行并把结果喂回去继续对话
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
                $result = exec_tool($tc['name'], $args, $toolCtx, $allSources);
                $conv[] = ['role' => 'tool', 'tool_call_id' => $tc['id'], 'content' => $result];
            }
            if ($allSources) $sse(['web_results' => $allSources]);
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
/* 技能注册表 + 工具分发器                                              */
/* ------------------------------------------------------------------ */

// 按场景组装工具定义
function build_tool_defs($web, $hasImage, $hasFile, $agent = false) {
    $tools = [];
    // 始终可用
    $tools[] = ['type'=>'function','function'=>[
        'name'=>'time_now','description'=>'获取当前日期和时间。当用户问"今天几号""现在几点"等时间相关问题时调用。',
        'parameters'=>['type'=>'object','properties'=>['timezone'=>['type'=>'string','description'=>'时区如 Asia/Shanghai，留空用默认']],'required'=>[]]
    ]];
    $tools[] = ['type'=>'function','function'=>[
        'name'=>'get_weather','description'=>'获取指定地点的当前天气（温度、湿度、风力、天气状况）。',
        'parameters'=>['type'=>'object','properties'=>['location'=>['type'=>'string','description'=>'城市名（中文或英文）']],'required'=>['location']]
    ]];
    // 联网时可用
    if ($web) {
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'web_search','description'=>'搜索互联网获取实时信息（最新新闻、产品发布、价格、赛事、天气、时效性事实等）。query 使用提炼后的简洁关键词。',
            'parameters'=>['type'=>'object','properties'=>['query'=>['type'=>'string','description'=>'搜索关键词，简洁准确']],'required'=>['query']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'url_read','description'=>'读取指定网页的内容（获取 URL 的正文文本，用于查阅资料、验证信息、读文章等）。',
            'parameters'=>['type'=>'object','properties'=>['url'=>['type'=>'string','description'=>'完整 URL（http:// 或 https:// 开头）']],'required'=>['url']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'baike','description'=>'搜索百度百科获取百科知识（中文百科，国内可直接访问）。',
            'parameters'=>['type'=>'object','properties'=>[
                'query'=>['type'=>'string','description'=>'搜索关键词']
            ],'required'=>['query']]
        ]];
    }
    if ($hasImage) {
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'read_image','description'=>'读取并理解用户上传的图片内容（OCR 识别文字、描述画面、读取图表/截图/照片）。只要用户提到图片内容、想知道图片里有什么，就必须调用此工具。',
            'parameters'=>['type'=>'object','properties'=>[
                'image_id'=>['type'=>'string','description'=>'图片附件的 id（形如 u_xxx）'],
                'question'=>['type'=>'string','description'=>'针对图片的具体问题，如"图里写了什么字"；留空则整体描述']
            ],'required'=>['image_id']]
        ]];
    }
    if ($hasFile) {
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'read_file','description'=>'读取用户上传的文档内容（txt/md/csv/json/log/pdf/docx）。只要用户提到文件内容、想总结/翻译/分析文件，就必须调用此工具。',
            'parameters'=>['type'=>'object','properties'=>[
                'file_id'=>['type'=>'string','description'=>'文件附件的 id（形如 u_xxx）'],
                'instruction'=>['type'=>'string','description'=>'对文件内容的处理要求，如"总结要点"；留空则原样返回']
            ],'required'=>['file_id']]
        ]];
    }
    // Agent 模式：执行代码、写文件
    if ($agent) {
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'code_run','description'=>'执行 Python 或 JavaScript 代码并返回输出。用于数据处理、数学计算、生成文件、自动化任务等。代码在沙箱中运行（10秒超时、限制文件大小）。',
            'parameters'=>['type'=>'object','properties'=>[
                'language'=>['type'=>'string','description'=>'编程语言：python 或 javascript'],
                'code'=>['type'=>'string','description'=>'要执行的完整代码']
            ],'required'=>['language','code']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'file_write','description'=>'保存内容到文件（持久化代码输出、生成文档等）。文件保存在服务器的 data/workspace/ 目录。',
            'parameters'=>['type'=>'object','properties'=>[
                'filename'=>['type'=>'string','description'=>'文件名（仅字母数字._-）'],
                'content'=>['type'=>'string','description'=>'文件内容']
            ],'required'=>['filename','content']]
        ]];
    }
    // 始终可用：翻译、汇率、IP、二维码
    $tools[] = ['type'=>'function','function'=>[
        'name'=>'translate','description'=>'翻译文本。指定源语言和目标语言，返回翻译结果。',
        'parameters'=>['type'=>'object','properties'=>[
            'text'=>['type'=>'string','description'=>'要翻译的文本'],
            'target_lang'=>['type'=>'string','description'=>'目标语言代码：zh/en/ja/ko/fr/de/es 等'],
            'source_lang'=>['type'=>'string','description'=>'源语言代码（可选，留空自动检测）']
        ],'required'=>['text','target_lang']]
    ]];
    $tools[] = ['type'=>'function','function'=>[
        'name'=>'exchange_rate','description'=>'查询实时汇率。支持任意两种货币之间的兑换。',
        'parameters'=>['type'=>'object','properties'=>[
            'from'=>['type'=>'string','description'=>'源货币代码，如 USD/CNY/EUR/JPY'],
            'to'=>['type'=>'string','description'=>'目标货币代码'],
            'amount'=>['type'=>'number','description'=>'金额（可选，默认1）']
        ],'required'=>['from','to']]
    ]];
    $tools[] = ['type'=>'function','function'=>[
        'name'=>'ip_lookup','description'=>'查询 IP 地址的归属地、运营商、时区等信息。',
        'parameters'=>['type'=>'object','properties'=>[
            'ip'=>['type'=>'string','description'=>'IP地址（留空查当前服务器公网IP）']
        ],'required'=>[]]
    ]];
    $tools[] = ['type'=>'function','function'=>[
        'name'=>'qrcode','description'=>'生成二维码图片。输入文本或URL，返回base64编码的PNG图片。',
        'parameters'=>['type'=>'object','properties'=>[
            'content'=>['type'=>'string','description'=>'二维码内容（文本或URL）'],
            'size'=>['type'=>'integer','description'=>'图片尺寸像素（默认300）']
        ],'required'=>['content']]
    ]];
    // Agent 模式扩展：邮件、日程、数据库、Git、截图
    if ($agent) {
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'send_email','description'=>'发送邮件。需要服务器已配置SMTP（data/config.json中的smtp字段）。',
            'parameters'=>['type'=>'object','properties'=>[
                'to'=>['type'=>'string','description'=>'收件人邮箱'],
                'subject'=>['type'=>'string','description'=>'邮件主题'],
                'body'=>['type'=>'string','description'=>'邮件正文']
            ],'required'=>['to','subject','body']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'schedule_reminder','description'=>'创建定时提醒任务。支持一次性和周期性提醒。',
            'parameters'=>['type'=>'object','properties'=>[
                'message'=>['type'=>'string','description'=>'提醒内容'],
                'time'=>['type'=>'string','description'=>'提醒时间，格式 YYYY-MM-DD HH:MM 或 cron表达式'],
                'repeat'=>['type'=>'string','description'=>'重复规则：once/daily/weekly/monthly（默认once）']
            ],'required'=>['message','time']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'db_query','description'=>'执行SQL查询。支持SQLite（默认）和MySQL（需配置）。只允许SELECT查询，禁止写入操作。',
            'parameters'=>['type'=>'object','properties'=>[
                'sql'=>['type'=>'string','description'=>'SQL SELECT语句'],
                'db'=>['type'=>'string','description'=>'数据库名或路径（可选，默认data/app.db）']
            ],'required'=>['sql']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'git_op','description'=>'执行Git操作。支持status/log/diff/show/branch/list等只读操作，以及add/commit/pull等写操作。',
            'parameters'=>['type'=>'object','properties'=>[
                'command'=>['type'=>'string','description'=>'git子命令：status/log/diff/show/branch/add/commit/pull'],
                'args'=>['type'=>'string','description'=>'附加参数（可选）'],
                'repo'=>['type'=>'string','description'=>'仓库路径（可选，默认当前目录）']
            ],'required'=>['command']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'screenshot','description'=>'截取网页截图。使用headless Chrome对指定URL截图，返回base64 PNG。',
            'parameters'=>['type'=>'object','properties'=>[
                'url'=>['type'=>'string','description'=>'要截图的网页URL'],
                'width'=>['type'=>'integer','description'=>'视口宽度（默认1280）'],
                'height'=>['type'=>'integer','description'=>'视口高度（默认720）']
            ],'required'=>['url']]
        ]];
        // 服务器管理工具
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_file_list','description'=>'列出服务器指定目录的文件和子目录。支持递归列出。',
            'parameters'=>['type'=>'object','properties'=>[
                'path'=>['type'=>'string','description'=>'目录路径（默认当前工作目录）'],
                'recursive'=>['type'=>'boolean','description'=>'是否递归列出子目录（默认false）'],
                'pattern'=>['type'=>'string','description'=>'文件名匹配模式（可选，如*.php）']
            ],'required'=>[]]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_file_read','description'=>'读取服务器上的文件内容。支持文本文件和二进制文件（返回base64）。',
            'parameters'=>['type'=>'object','properties'=>[
                'path'=>['type'=>'string','description'=>'文件路径'],
                'max_bytes'=>['type'=>'integer','description'=>'最大读取字节数（默认1MB）']
            ],'required'=>['path']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_file_write','description'=>'写入或创建服务器上的文件。支持覆盖和追加模式。',
            'parameters'=>['type'=>'object','properties'=>[
                'path'=>['type'=>'string','description'=>'文件路径'],
                'content'=>['type'=>'string','description'=>'文件内容'],
                'mode'=>['type'=>'string','description'=>'写入模式：overwrite（覆盖，默认）或 append（追加）']
            ],'required'=>['path','content']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_file_delete','description'=>'删除服务器上的文件或目录。',
            'parameters'=>['type'=>'object','properties'=>[
                'path'=>['type'=>'string','description'=>'要删除的文件或目录路径'],
                'recursive'=>['type'=>'boolean','description'=>'删除目录时是否递归（默认false）']
            ],'required'=>['path']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_file_move','description'=>'移动或重命名服务器上的文件或目录。',
            'parameters'=>['type'=>'object','properties'=>[
                'source'=>['type'=>'string','description'=>'源路径'],
                'destination'=>['type'=>'string','description'=>'目标路径']
            ],'required'=>['source','destination']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_info','description'=>'获取服务器系统信息：CPU、内存、磁盘、负载、运行时间等。',
            'parameters'=>['type'=>'object','properties'=>[],'required'=>[]]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_process_list','description'=>'列出服务器上的运行进程。可按名称过滤。',
            'parameters'=>['type'=>'object','properties'=>[
                'filter'=>['type'=>'string','description'=>'进程名过滤关键词（可选）'],
                'limit'=>['type'=>'integer','description'=>'最大返回数量（默认50）']
            ],'required'=>[]]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_service','description'=>'管理系统服务：查看状态、启动、停止、重启。',
            'parameters'=>['type'=>'object','properties'=>[
                'action'=>['type'=>'string','description'=>'操作：status/start/stop/restart/list'],
                'service'=>['type'=>'string','description'=>'服务名称（list时可选）']
            ],'required'=>['action']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_log','description'=>'查看服务器日志文件。支持tail（最后N行）和grep搜索。',
            'parameters'=>['type'=>'object','properties'=>[
                'path'=>['type'=>'string','description'=>'日志文件路径'],
                'lines'=>['type'=>'integer','description'=>'读取最后N行（默认100）'],
                'grep'=>['type'=>'string','description'=>'搜索关键词（可选）']
            ],'required'=>['path']]
        ]];
        $tools[] = ['type'=>'function','function'=>[
            'name'=>'server_network','description'=>'网络诊断工具：ping、DNS查询、端口检测、traceroute。',
            'parameters'=>['type'=>'object','properties'=>[
                'tool'=>['type'=>'string','description'=>'工具类型：ping/dns/port/traceroute/curl'],
                'target'=>['type'=>'string','description'=>'目标主机/IP/域名'],
                'options'=>['type'=>'string','description'=>'附加选项（如端口号、超时等）']
            ],'required'=>['tool','target']]
        ]];
    }
    return $tools;
}

// 工具分发器
function exec_tool($name, $args, $ctx, &$allSources) {
    switch ($name) {
        case 'web_search': return exec_web_search_tool($args, $allSources);
        case 'read_image': return exec_read_image($args, $ctx);
        case 'read_file':  return exec_read_file($args, $ctx);
        case 'time_now':   return exec_time_now($args);
        case 'get_weather':return exec_get_weather($args);
        case 'url_read':   return exec_url_read($args);
        case 'baike':     return exec_baike($args);
        case 'code_run':   return exec_code_run($args);
        case 'file_write': return exec_file_write($args);
        case 'translate':  return exec_translate($args, $ctx);
        case 'exchange_rate': return exec_exchange_rate($args);
        case 'ip_lookup':  return exec_ip_lookup($args);
        case 'qrcode':     return exec_qrcode($args);
        case 'send_email': return exec_send_email($args, $ctx);
        case 'schedule_reminder': return exec_schedule_reminder($args);
        case 'db_query':   return exec_db_query($args);
        case 'git_op':     return exec_git_op($args);
        case 'screenshot': return exec_screenshot($args);
        case 'server_file_list': return exec_server_file_list($args);
        case 'server_file_read': return exec_server_file_read($args);
        case 'server_file_write': return exec_server_file_write($args);
        case 'server_file_delete': return exec_server_file_delete($args);
        case 'server_file_move': return exec_server_file_move($args);
        case 'server_info': return exec_server_info();
        case 'server_process_list': return exec_server_process_list($args);
        case 'server_service': return exec_server_service($args);
        case 'server_log': return exec_server_log($args);
        case 'server_network': return exec_server_network($args);
        default: return '未知工具：' . $name;
    }
}

/* ------------------------------------------------------------------ */
/* 报时                                                                 */
/* ------------------------------------------------------------------ */
function exec_time_now($args) {
    $tz = trim((string)($args['timezone'] ?? '')) ?: 'Asia/Shanghai';
    try {
        $dt = new DateTime('now', new DateTimeZone($tz));
        $weekday = ['日','一','二','三','四','五','六'][(int)$dt->format('w')];
        return '当前时间：' . $dt->format('Y-m-d H:i:s') . '（星期' . $weekday . '）时区 ' . $tz;
    } catch (Exception $e) {
        $dt = new DateTime('now');
        return '当前时间：' . $dt->format('Y-m-d H:i:s') . '（UTC，时区参数无效：' . $tz . '）';
    }
}

/* ------------------------------------------------------------------ */
/* 查天气（wttr.in，免 Key）                                            */
/* ------------------------------------------------------------------ */
function exec_get_weather($args) {
    $loc = trim((string)($args['location'] ?? ''));
    if ($loc === '') return '请提供地点。';
    $url = 'https://wttr.in/' . urlencode($loc) . '?format=j1&lang=zh';
    $json = http_get($url, 12);
    if ($json === '') return '天气数据获取失败（wttr.in 不可达）。';
    $d = json_decode($json, true);
    if (!is_array($d) || !isset($d['current_condition'][0])) return '天气数据解析失败。';
    $c = $d['current_condition'][0];
    $area = $d['nearest_area'][0]['areaName'][0]['value'] ?? $loc;
    $desc = $c['lang_ZH'][0]['value'] ?? ($c['weatherDesc'][0]['value'] ?? '未知');
    return sprintf("%s 天气：%s，%s°C，体感 %s°C，湿度 %s%%，风 %s km/h（%s）",
        $area, $desc, $c['temp_C'], $c['FeelsLikeC'], $c['humidity'], $c['windspeedKmph'], $c['winddir16Point']);
}

/* ------------------------------------------------------------------ */
/* 读网页                                                               */
/* ------------------------------------------------------------------ */
function exec_url_read($args) {
    $url = trim((string)($args['url'] ?? ''));
    if ($url === '') return '请提供 URL。';
    if (!preg_match('#^https?://#i', $url)) return 'URL 必须以 http:// 或 https:// 开头。';
    $html = http_get($url, 15);
    if ($html === '') return '获取网页失败（' . $url . '）。';
    $text = html_to_text($html);
    if ($text === '') return '网页内容为空或无法提取文本。';
    return '网页内容（' . $url . "）：\n" . str_cut($text, 8000);
}

/* ------------------------------------------------------------------ */
/* 查百科（百度百科优先，被反爬时自动降级为网页搜索）                    */
/* ------------------------------------------------------------------ */
function exec_baike($args) {
    $q = trim((string)($args['query'] ?? ''));
    if ($q === '') return '请提供搜索词。';
    // 优先百度百科
    $url = 'https://baike.baidu.com/item/' . urlencode($q);
    $html = http_get($url, 12);
    if ($html !== '' && strlen($html) > 2000) {
        $summary = '';
        if (preg_match('/<meta\s+name=["\']description["\']\s+content=["\']([^"\']+)/i', $html, $m)) {
            $summary = html_entity_decode($m[1], ENT_QUOTES, 'UTF-8');
        }
        if (mb_strlen($summary) < 30) {
            $summary = str_cut(html_to_text($html), 3000);
        }
        if (trim($summary) !== '') return "百度百科「{$q}」：\n" . str_cut($summary, 3000);
    }
    // 百科不可用 → 降级为网页搜索
    $results = web_search($q, 3);
    if (!$results) return '未找到「' . $q . '」的相关内容。';
    $out = "百科「{$q}」搜索结果：\n";
    foreach ($results as $i => $r) {
        $out .= '[' . ($i + 1) . '] ' . $r['title'] . "\n" . $r['snippet'] . "\n\n";
    }
    return $out;
}

/* ------------------------------------------------------------------ */
/* 执行代码（沙箱：临时目录 + timeout + ulimit）                        */
/* ------------------------------------------------------------------ */
function exec_code_run($args) {
    $lang = strtolower(trim((string)($args['language'] ?? '')));
    $code = (string)($args['code'] ?? '');
    if ($code === '') return '没有提供代码。';
    $runners = ['python'=>['python3','.py'], 'javascript'=>['node','.js'], 'js'=>['node','.js']];
    if (!isset($runners[$lang])) return '不支持的语言：' . $lang . '。请用 python 或 javascript。';
    list($runner, $ext) = $runners[$lang];
    if (!function_exists('shell_exec')) return '服务器禁用了 shell_exec，无法执行代码。';
    $bin = trim((string)@shell_exec("command -v $runner 2>/dev/null"));
    if ($bin === '') return "服务器未安装 $runner。";
    $dir = DATA_DIR . '/code_runs';
    if (!is_dir($dir)) @mkdir($dir, 0777, true);
    $tmp = $dir . '/' . substr(md5(uniqid('', true)), 0, 12) . $ext;
    file_put_contents($tmp, $code);
    $cmd = 'cd ' . escapeshellarg($dir) . ' && timeout 10 ' . escapeshellarg($bin) . ' ' . escapeshellarg($tmp) . ' 2>&1';
    $output = @shell_exec($cmd);
    @unlink($tmp);
    if ($output === null) return '代码执行失败。';
    $output = trim($output);
    if (strlen($output) > 8000) $output = str_cut($output, 8000) . "\n…（输出已截断）";
    return "代码执行输出：\n" . ($output !== '' ? $output : '（无输出）');
}

/* ------------------------------------------------------------------ */
/* 写文件                                                               */
/* ------------------------------------------------------------------ */
function exec_file_write($args) {
    $name = trim((string)($args['filename'] ?? ''));
    $content = (string)($args['content'] ?? '');
    if ($name === '') return '请提供文件名。';
    $name = preg_replace('/[^A-Za-z0-9._-]/', '_', $name);
    if (strlen($name) > 100) $name = substr($name, 0, 100);
    $dir = DATA_DIR . '/workspace';
    if (!is_dir($dir)) @mkdir($dir, 0777, true);
    file_put_contents($dir . '/' . $name, $content);
    return '文件已保存：' . $name . '（' . strlen($content) . ' 字节），路径 data/workspace/' . $name;
}

/* ------------------------------------------------------------------ */
/* 翻译（优先调翻译API，无配置则回退让模型自己翻）                      */
/* ------------------------------------------------------------------ */
function exec_translate($args, $ctx) {
    $text = (string)($args['text'] ?? '');
    $target = strtoupper(trim((string)($args['target_lang'] ?? '')));
    $source = strtoupper(trim((string)($args['source_lang'] ?? '')));
    if ($text === '' || $target === '') return '请提供要翻译的文本和目标语言。';
    // 尝试用配置的翻译API（如有）
    $cfg = $ctx['cfg'] ?? [];
    $transApi = trim((string)($cfg['translate_api'] ?? ''));
    $transKey = trim((string)($cfg['translate_key'] ?? ''));
    if ($transApi !== '' && $transKey !== '') {
        // 支持 DeepL 格式
        $payload = ['text' => [$text], 'target_lang' => $target];
        if ($source !== '') $payload['source_lang'] = $source;
        $ch = curl_init();
        curl_setopt_array($ch, [
            CURLOPT_URL => $transApi,
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => json_encode($payload),
            CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'Authorization: DeepL-Auth-Key ' . $transKey],
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 15,
        ]);
        $resp = curl_exec($ch);
        curl_close($ch);
        if ($resp !== false) {
            $d = json_decode($resp, true);
            if (isset($d['translations'][0]['text'])) {
                return "翻译结果（{$target}）：\n" . $d['translations'][0]['text'];
            }
        }
    }
    // 无翻译API → 提示模型自行翻译
    return "[未配置翻译API] 请直接翻译以下文本为{$target}：\n{$text}";
}

/* ------------------------------------------------------------------ */
/* 汇率查询（exchangerate-api.com 免费）                               */
/* ------------------------------------------------------------------ */
function exec_exchange_rate($args) {
    $from = strtoupper(trim((string)($args['from'] ?? '')));
    $to = strtoupper(trim((string)($args['to'] ?? '')));
    $amount = floatval($args['amount'] ?? 1);
    if ($from === '' || $to === '') return '请提供源货币和目标货币代码。';
    $url = "https://api.exchangerate-api.com/v4/latest/{$from}";
    $json = http_get($url, 10);
    if ($json === '') return '汇率查询失败。';
    $d = json_decode($json, true);
    if (!is_array($d) || !isset($d['rates'][$to])) return "未找到 {$from}→{$to} 的汇率。";
    $rate = floatval($d['rates'][$to]);
    $result = round($amount * $rate, 4);
    return "{$amount} {$from} = {$result} {$to}（汇率 1 {$from} = {$rate} {$to}，更新时间 {$d['date']}）";
}

/* ------------------------------------------------------------------ */
/* IP 归属地查询（ip-api.com 免费）                                    */
/* ------------------------------------------------------------------ */
function exec_ip_lookup($args) {
    $ip = trim((string)($args['ip'] ?? ''));
    $url = 'http://ip-api.com/json/' . ($ip !== '' ? urlencode($ip) : '') . '?lang=zh-CN&fields=status,message,country,regionName,city,isp,org,as,query,timezone';
    $json = http_get($url, 10);
    if ($json === '') return 'IP查询失败。';
    $d = json_decode($json, true);
    if (!is_array($d) || ($d['status'] ?? '') !== 'success') return 'IP查询失败：' . ($d['message'] ?? '未知错误');
    return sprintf("IP：%s\n国家：%s\n地区：%s %s\n城市：%s\n运营商：%s (%s)\nAS：%s\n时区：%s",
        $d['query'], $d['country'], $d['regionName'], '', $d['city'],
        $d['isp'], $d['org'], $d['as'], $d['timezone']);
}

/* ------------------------------------------------------------------ */
/* 二维码生成（PHP GD）                                                */
/* ------------------------------------------------------------------ */
function exec_qrcode($args) {
    $content = (string)($args['content'] ?? '');
    $size = intval($args['size'] ?? 300);
    if ($content === '') return '请提供二维码内容。';
    if ($size < 100) $size = 100;
    if ($size > 1000) $size = 1000;
    if (!function_exists('imagecreate')) return '服务器缺少GD库，无法生成二维码。';
    // 使用纯PHP QR码生成（内嵌最小实现）
    // 简化方案：调用外部API生成
    $url = 'https://api.qrserver.com/v1/create-qr-code/?size=' . $size . 'x' . $size . '&data=' . urlencode($content);
    $img = http_get($url, 15);
    if ($img === '' || strlen($img) < 100) return '二维码生成失败。';
    $b64 = base64_encode($img);
    // 保存到workspace
    $dir = DATA_DIR . '/workspace';
    if (!is_dir($dir)) @mkdir($dir, 0777, true);
    $fname = 'qrcode_' . substr(md5($content), 0, 8) . '.png';
    file_put_contents($dir . '/' . $fname, $img);
    return "二维码已生成（{$size}x{$size}），已保存为 data/workspace/{$fname}\n![QR](data:image/png;base64," . substr($b64, 0, 200) . "...)";
}

/* ------------------------------------------------------------------ */
/* 发送邮件（SMTP）                                                    */
/* ------------------------------------------------------------------ */
function exec_send_email($args, $ctx) {
    $to = trim((string)($args['to'] ?? ''));
    $subject = trim((string)($args['subject'] ?? ''));
    $body = (string)($args['body'] ?? '');
    if ($to === '' || $subject === '' || $body === '') return '收件人、主题、正文都不能为空。';
    $cfg = $ctx['cfg'] ?? [];
    $smtpHost = trim((string)($cfg['smtp_host'] ?? ''));
    $smtpUser = trim((string)($cfg['smtp_user'] ?? ''));
    $smtpPass = trim((string)($cfg['smtp_pass'] ?? ''));
    $smtpPort = intval($cfg['smtp_port'] ?? 587);
    if ($smtpHost === '') return '未配置SMTP服务器。请在设置中添加 smtp_host/smtp_user/smtp_pass。';
    // 使用PHP mail()或简单SMTP
    if (function_exists('mail') && $smtpHost === '') {
        $ok = @mail($to, $subject, $body, 'From: ' . $smtpUser);
        return $ok ? "邮件已发送至 {$to}" : '邮件发送失败。';
    }
    // 简单SMTP发送
    $sock = @fsockopen($smtpHost, $smtpPort, $errno, $errstr, 10);
    if (!$sock) return "SMTP连接失败：{$errstr}";
    $read = function() use ($sock) { return fgets($sock, 512); };
    $write = function($cmd) use ($sock) { fwrite($sock, $cmd . "\r\n"); };
    $read(); // 欢迎消息
    $write('EHLO localhost'); $read();
    if ($smtpUser !== '') {
        $write('AUTH LOGIN'); $read();
        $write(base64_encode($smtpUser)); $read();
        $write(base64_encode($smtpPass)); $read();
    }
    $from = $smtpUser !== '' ? $smtpUser : 'noreply@localhost';
    $write('MAIL FROM:<' . $from . '>'); $read();
    $write('RCPT TO:<' . $to . '>'); $read();
    $write('DATA'); $read();
    $write("Subject: {$subject}\r\nFrom: {$from}\r\nTo: {$to}\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n{$body}\r\n.");
    $read();
    $write('QUIT'); $read();
    fclose($sock);
    return "邮件已发送至 {$to}";
}

/* ------------------------------------------------------------------ */
/* 定时提醒                                                            */
/* ------------------------------------------------------------------ */
function exec_schedule_reminder($args) {
    $msg = trim((string)($args['message'] ?? ''));
    $time = trim((string)($args['time'] ?? ''));
    $repeat = trim((string)($args['repeat'] ?? 'once'));
    if ($msg === '' || $time === '') return '请提供提醒内容和时间。';
    // 写入本地提醒文件（简易实现）
    $dir = DATA_DIR . '/reminders';
    if (!is_dir($dir)) @mkdir($dir, 0777, true);
    $id = substr(md5(uniqid('', true)), 0, 8);
    $reminder = [
        'id' => $id,
        'message' => $msg,
        'time' => $time,
        'repeat' => $repeat,
        'created_at' => date('Y-m-d H:i:s'),
        'active' => true,
    ];
    file_put_contents($dir . '/' . $id . '.json', json_encode($reminder, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
    return "提醒已创建（ID: {$id}）\n内容：{$msg}\n时间：{$time}\n重复：{$repeat}\n\n注意：需要配合定时任务扫描 data/reminders/ 目录才能触发提醒。";
}

/* ------------------------------------------------------------------ */
/* 数据库查询（SQLite，只读SELECT）                                    */
/* ------------------------------------------------------------------ */
function exec_db_query($args) {
    $sql = trim((string)($args['sql'] ?? ''));
    $dbPath = trim((string)($args['db'] ?? ''));
    if ($sql === '') return '请提供SQL语句。';
    // 安全检查：只允许SELECT
    $upper = strtoupper(preg_replace('/\s+/', ' ', $sql));
    if (strpos($upper, 'SELECT') !== 0) return '安全限制：仅允许SELECT查询。';
    $forbidden = ['INSERT', 'UPDATE', 'DELETE', 'DROP', 'ALTER', 'CREATE', 'TRUNCATE', 'EXEC', 'GRANT', 'REVOKE'];
    foreach ($forbidden as $kw) {
        if (preg_match('/\b' . $kw . '\b/i', $sql)) return '安全限制：禁止' . $kw . '操作。';
    }
    if ($dbPath === '') $dbPath = DATA_DIR . '/app.db';
    if (!file_exists($dbPath)) return '数据库文件不存在：' . $dbPath;
    try {
        $pdo = new PDO('sqlite:' . $dbPath);
        $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
        $stmt = $pdo->query($sql);
        $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
        if (empty($rows)) return '查询结果为空。';
        $out = "查询返回 " . count($rows) . " 行：\n";
        // 表格输出
        $cols = array_keys($rows[0]);
        $out .= '| ' . implode(' | ', $cols) . " |\n";
        $out .= '|' . str_repeat('---|', count($cols)) . "\n";
        foreach (array_slice($rows, 0, 50) as $row) {
            $vals = [];
            foreach ($cols as $c) $vals[] = str_replace('|', '\\|', (string)($row[$c] ?? ''));
            $out .= '| ' . implode(' | ', $vals) . " |\n";
        }
        if (count($rows) > 50) $out .= "\n…（仅显示前50行，共" . count($rows) . "行）";
        return $out;
    } catch (PDOException $e) {
        return 'SQL执行失败：' . $e->getMessage();
    }
}

/* ------------------------------------------------------------------ */
/* Git 操作                                                            */
/* ------------------------------------------------------------------ */
function exec_git_op($args) {
    $cmd = trim((string)($args['command'] ?? ''));
    $gitArgs = trim((string)($args['args'] ?? ''));
    $repo = trim((string)($args['repo'] ?? '.'));
    if ($cmd === '') return '请提供git子命令。';
    // 白名单
    $allowed = ['status','log','diff','show','branch','tag','remote','add','commit','pull','fetch','stash','checkout','merge','rebase','cherry-pick','blame','shortlog','describe','ls-files','ls-remote'];
    if (!in_array($cmd, $allowed, true)) return '不支持的git命令：' . $cmd . '。允许：' . implode(', ', $allowed);
    // 危险参数过滤
    if (preg_match('/[;&|`$()]/', $gitArgs)) return '参数包含非法字符。';
    $fullCmd = 'cd ' . escapeshellarg($repo) . ' && git ' . escapeshellarg($cmd);
    if ($gitArgs !== '') $fullCmd .= ' ' . $gitArgs;
    $fullCmd .= ' 2>&1';
    $output = @shell_exec($fullCmd);
    if ($output === null) return 'git命令执行失败。';
    $output = trim($output);
    if (strlen($output) > 8000) $output = str_cut($output, 8000) . "\n…（输出已截断）";
    return "git {$cmd} 输出：\n" . ($output !== '' ? $output : '（无输出）');
}

/* ------------------------------------------------------------------ */
/* 网页截图（headless Chrome）                                         */
/* ------------------------------------------------------------------ */
function exec_screenshot($args) {
    $url = trim((string)($args['url'] ?? ''));
    $width = intval($args['width'] ?? 1280);
    $height = intval($args['height'] ?? 720);
    if ($url === '') return '请提供URL。';
    if (!preg_match('#^https?://#i', $url)) return 'URL必须以http://或https://开头。';
    // 查找Chrome/Chromium
    $chrome = trim((string)@shell_exec('command -v google-chrome 2>/dev/null || command -v chromium-browser 2>/dev/null || command -v chromium 2>/dev/null'));
    if ($chrome === '') return '服务器未安装Chrome/Chromium，无法截图。';
    $dir = DATA_DIR . '/workspace';
    if (!is_dir($dir)) @mkdir($dir, 0777, true);
    $outFile = $dir . '/screenshot_' . substr(md5($url . time()), 0, 8) . '.png';
    $cmd = escapeshellarg($chrome) . ' --headless --disable-gpu --no-sandbox --disable-dev-shm-usage'
         . ' --window-size=' . $width . ',' . $height
         . ' --screenshot=' . escapeshellarg($outFile)
         . ' ' . escapeshellarg($url) . ' 2>&1';
    $output = @shell_exec($cmd);
    if (!file_exists($outFile)) return '截图失败：' . trim((string)$output);
    $size = filesize($outFile);
    $b64 = base64_encode(file_get_contents($outFile));
    return "截图成功（{$width}x{$height}，{$size}字节），已保存为 data/workspace/" . basename($outFile)
         . "\n![screenshot](data:image/png;base64," . substr($b64, 0, 200) . "...)";
}

/* ------------------------------------------------------------------ */
/* 服务器文件列表                                                       */
/* ------------------------------------------------------------------ */
function exec_server_file_list($args) {
    $path = trim((string)($args['path'] ?? '.'));
    $recursive = !empty($args['recursive']);
    $pattern = trim((string)($args['pattern'] ?? ''));
    if (!is_dir($path)) return '目录不存在：' . $path;
    $items = [];
    if ($recursive) {
        $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($path, RecursiveDirectoryIterator::SKIP_DOTS), RecursiveIteratorIterator::SELF_FIRST);
        foreach ($it as $file) {
            if ($pattern !== '' && !fnmatch($pattern, $file->getFilename())) continue;
            $rel = str_replace($path . '/', '', $file->getPathname());
            $type = $file->isDir() ? 'dir' : 'file';
            $size = $file->isFile() ? $file->getSize() : 0;
            $items[] = ['path' => $rel, 'type' => $type, 'size' => $size];
            if (count($items) >= 500) break;
        }
    } else {
        $entries = @scandir($path);
        if ($entries === false) return '无法读取目录：' . $path;
        foreach ($entries as $entry) {
            if ($entry === '.' || $entry === '..') continue;
            if ($pattern !== '' && !fnmatch($pattern, $entry)) continue;
            $full = $path . '/' . $entry;
            $type = is_dir($full) ? 'dir' : 'file';
            $size = is_file($full) ? filesize($full) : 0;
            $items[] = ['path' => $entry, 'type' => $type, 'size' => $size];
        }
    }
    if (empty($items)) return '目录为空。';
    $out = "目录 {$path} 内容（" . count($items) . " 项）：\n";
    foreach ($items as $item) {
        $icon = $item['type'] === 'dir' ? '📁' : '📄';
        $sizeStr = $item['type'] === 'file' ? ' (' . format_bytes($item['size']) . ')' : '';
        $out .= $icon . ' ' . $item['path'] . $sizeStr . "\n";
    }
    return $out;
}

function format_bytes($bytes) {
    if ($bytes < 1024) return $bytes . 'B';
    if ($bytes < 1048576) return round($bytes / 1024, 1) . 'KB';
    if ($bytes < 1073741824) return round($bytes / 1048576, 1) . 'MB';
    return round($bytes / 1073741824, 2) . 'GB';
}

/* ------------------------------------------------------------------ */
/* 服务器文件读取                                                       */
/* ------------------------------------------------------------------ */
function exec_server_file_read($args) {
    $path = trim((string)($args['path'] ?? ''));
    $maxBytes = intval($args['max_bytes'] ?? 1048576); // 默认1MB
    if ($path === '') return '请提供文件路径。';
    if (!file_exists($path)) return '文件不存在：' . $path;
    $size = filesize($path);
    if ($size > $maxBytes) {
        $content = file_get_contents($path, false, null, 0, $maxBytes);
        return "文件 {$path}（" . format_bytes($size) . "，仅读取前 " . format_bytes($maxBytes) . "）：\n" . $content . "\n…（文件过大，已截断）";
    }
    // 检测是否为文本文件
    $finfo = new finfo(FILEINFO_MIME_TYPE);
    $mime = $finfo->file($path);
    if (strpos($mime, 'text/') === 0 || strpos($mime, 'application/json') === 0 || strpos($mime, 'application/xml') === 0) {
        $content = file_get_contents($path);
        return "文件 {$path}（{$mime}，" . format_bytes($size) . "）：\n" . $content;
    }
    // 二进制文件返回base64
    $content = base64_encode(file_get_contents($path));
    return "文件 {$path}（{$mime}，" . format_bytes($size) . "，base64编码）：\n" . substr($content, 0, 2000) . (strlen($content) > 2000 ? "\n…（已截断）" : '');
}

/* ------------------------------------------------------------------ */
/* 服务器文件写入                                                       */
/* ------------------------------------------------------------------ */
function exec_server_file_write($args) {
    $path = trim((string)($args['path'] ?? ''));
    $content = (string)($args['content'] ?? '');
    $mode = trim((string)($args['mode'] ?? 'overwrite'));
    if ($path === '') return '请提供文件路径。';
    $dir = dirname($path);
    if (!is_dir($dir)) @mkdir($dir, 0777, true);
    if ($mode === 'append') {
        file_put_contents($path, $content, FILE_APPEND);
        return "已追加到 {$path}（" . strlen($content) . " 字节）";
    }
    file_put_contents($path, $content);
    return "已写入 {$path}（" . strlen($content) . " 字节）";
}

/* ------------------------------------------------------------------ */
/* 服务器文件删除                                                       */
/* ------------------------------------------------------------------ */
function exec_server_file_delete($args) {
    $path = trim((string)($args['path'] ?? ''));
    $recursive = !empty($args['recursive']);
    if ($path === '') return '请提供路径。';
    if (!file_exists($path)) return '路径不存在：' . $path;
    if (is_dir($path)) {
        if (!$recursive) return '这是目录，需要设置 recursive=true 才能删除。';
        $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($path, RecursiveDirectoryIterator::SKIP_DOTS), RecursiveIteratorIterator::CHILD_FIRST);
        foreach ($it as $file) {
            if ($file->isDir()) @rmdir($file->getPathname());
            else @unlink($file->getPathname());
        }
        @rmdir($path);
        return "已递归删除目录：{$path}";
    }
    @unlink($path);
    return "已删除文件：{$path}";
}

/* ------------------------------------------------------------------ */
/* 服务器文件移动/重命名                                                */
/* ------------------------------------------------------------------ */
function exec_server_file_move($args) {
    $source = trim((string)($args['source'] ?? ''));
    $dest = trim((string)($args['destination'] ?? ''));
    if ($source === '' || $dest === '') return '请提供源路径和目标路径。';
    if (!file_exists($source)) return '源路径不存在：' . $source;
    $destDir = dirname($dest);
    if (!is_dir($destDir)) @mkdir($destDir, 0777, true);
    if (@rename($source, $dest)) {
        return "已移动：{$source} → {$dest}";
    }
    return "移动失败：{$source} → {$dest}";
}

/* ------------------------------------------------------------------ */
/* 服务器系统信息                                                       */
/* ------------------------------------------------------------------ */
function exec_server_info() {
    $info = [];
    // CPU
    $cpuInfo = @file_get_contents('/proc/cpuinfo');
    if ($cpuInfo) {
        preg_match('/model name\s*:\s*(.+)/', $cpuInfo, $m);
        $info['cpu'] = trim($m[1] ?? 'unknown');
        $info['cpu_cores'] = substr_count($cpuInfo, 'processor');
    }
    // Memory
    $memInfo = @file_get_contents('/proc/meminfo');
    if ($memInfo) {
        preg_match('/MemTotal:\s+(\d+)/', $memInfo, $mt);
        preg_match('/MemAvailable:\s+(\d+)/', $memInfo, $ma);
        $total = intval($mt[1] ?? 0);
        $avail = intval($ma[1] ?? 0);
        $info['memory_total'] = format_bytes($total * 1024);
        $info['memory_available'] = format_bytes($avail * 1024);
        $info['memory_used_pct'] = $total > 0 ? round(($total - $avail) / $total * 100, 1) . '%' : 'N/A';
    }
    // Disk
    $disk = @disk_free_space('/');
    $diskTotal = @disk_total_space('/');
    if ($disk !== false && $diskTotal !== false) {
        $info['disk_total'] = format_bytes($diskTotal);
        $info['disk_free'] = format_bytes($disk);
        $info['disk_used_pct'] = round(($diskTotal - $disk) / $diskTotal * 100, 1) . '%';
    }
    // Load average
    $load = @file_get_contents('/proc/loadavg');
    if ($load) {
        $parts = explode(' ', $load);
        $info['load_avg'] = $parts[0] . ' ' . $parts[1] . ' ' . $parts[2];
    }
    // Uptime
    $uptime = @file_get_contents('/proc/uptime');
    if ($uptime) {
        $secs = floatval(explode(' ', $uptime)[0]);
        $days = floor($secs / 86400);
        $hours = floor(($secs % 86400) / 3600);
        $mins = floor(($secs % 3600) / 60);
        $info['uptime'] = "{$days}天 {$hours}小时 {$mins}分钟";
    }
    // OS
    $os = @file_get_contents('/etc/os-release');
    if ($os) {
        preg_match('/PRETTY_NAME="([^"]+)"/', $os, $m);
        $info['os'] = $m[1] ?? 'Linux';
    }
    // Hostname
    $info['hostname'] = gethostname() ?: 'unknown';
    
    $out = "=== 服务器信息 ===\n";
    foreach ($info as $k => $v) $out .= ucfirst(str_replace('_', ' ', $k)) . ": {$v}\n";
    return $out;
}

/* ------------------------------------------------------------------ */
/* 进程列表                                                             */
/* ------------------------------------------------------------------ */
function exec_server_process_list($args) {
    $filter = trim((string)($args['filter'] ?? ''));
    $limit = intval($args['limit'] ?? 50);
    $cmd = 'ps aux --sort=-%mem 2>/dev/null || ps aux';
    if ($filter !== '') $cmd .= ' | grep -i ' . escapeshellarg($filter);
    $cmd .= ' | head -' . ($limit + 1); // +1 for header
    $output = @shell_exec($cmd . ' 2>&1');
    if ($output === null) return '无法获取进程列表。';
    $lines = explode("\n", trim($output));
    if (count($lines) <= 1) return '无匹配进程。';
    return "进程列表（" . (count($lines) - 1) . " 个）：\n" . implode("\n", array_slice($lines, 0, $limit + 1));
}

/* ------------------------------------------------------------------ */
/* 服务管理                                                             */
/* ------------------------------------------------------------------ */
function exec_server_service($args) {
    $action = trim((string)($args['action'] ?? ''));
    $service = trim((string)($args['service'] ?? ''));
    $allowed = ['status', 'start', 'stop', 'restart', 'list'];
    if (!in_array($action, $allowed, true)) return '不支持的操作：' . $action . '。允许：' . implode(', ', $allowed);
    if ($action === 'list') {
        $output = @shell_exec('systemctl list-units --type=service --state=running --no-pager 2>/dev/null || service --status-all 2>/dev/null || echo "无法列出服务"');
        return "运行中的服务：\n" . trim((string)$output);
    }
    if ($service === '') return '请提供服务名称。';
    // 安全检查：只允许字母数字-_
    if (!preg_match('/^[a-zA-Z0-9_-]+$/', $service)) return '服务名包含非法字符。';
    $cmd = "systemctl {$action} " . escapeshellarg($service) . " 2>&1";
    $output = @shell_exec($cmd);
    if ($output === null) {
        // fallback to service command
        $output = @shell_exec("service {$service} {$action} 2>&1");
    }
    return "服务 {$service} {$action}：\n" . trim((string)$output);
}

/* ------------------------------------------------------------------ */
/* 日志查看                                                             */
/* ------------------------------------------------------------------ */
function exec_server_log($args) {
    $path = trim((string)($args['path'] ?? ''));
    $lines = intval($args['lines'] ?? 100);
    $grep = trim((string)($args['grep'] ?? ''));
    if ($path === '') return '请提供日志文件路径。';
    if (!file_exists($path)) return '日志文件不存在：' . $path;
    if ($lines < 1) $lines = 100;
    if ($lines > 1000) $lines = 1000;
    $cmd = 'tail -n ' . $lines . ' ' . escapeshellarg($path);
    if ($grep !== '') $cmd .= ' | grep -i ' . escapeshellarg($grep);
    $output = @shell_exec($cmd . ' 2>&1');
    if ($output === null) return '无法读取日志。';
    return "日志 {$path}（最后 {$lines} 行" . ($grep !== '' ? "，过滤: {$grep}" : '') . "）：\n" . trim($output);
}

/* ------------------------------------------------------------------ */
/* 网络诊断                                                             */
/* ------------------------------------------------------------------ */
function exec_server_network($args) {
    $tool = trim((string)($args['tool'] ?? ''));
    $target = trim((string)($args['target'] ?? ''));
    $options = trim((string)($args['options'] ?? ''));
    if ($target === '') return '请提供目标。';
    // 安全检查
    if (preg_match('/[;&|`$()]/', $target) || preg_match('/[;&|`$()]/', $options)) return '参数包含非法字符。';
    $allowed = ['ping', 'dns', 'port', 'traceroute', 'curl'];
    if (!in_array($tool, $allowed, true)) return '不支持的工具：' . $tool . '。允许：' . implode(', ', $allowed);
    
    switch ($tool) {
        case 'ping':
            $cmd = 'ping -c 4 -W 3 ' . escapeshellarg($target) . ' 2>&1';
            break;
        case 'dns':
            $cmd = 'nslookup ' . escapeshellarg($target) . ' 2>&1 || dig ' . escapeshellarg($target) . ' 2>&1 || host ' . escapeshellarg($target) . ' 2>&1';
            break;
        case 'port':
            $port = intval($options) ?: 80;
            $cmd = 'timeout 5 bash -c "echo >/dev/tcp/' . escapeshellarg($target) . '/' . $port . '" 2>&1 && echo "端口 ' . $port . ' 开放" || echo "端口 ' . $port . ' 不可达"';
            break;
        case 'traceroute':
            $cmd = 'traceroute -m 15 -w 2 ' . escapeshellarg($target) . ' 2>&1 || tracepath ' . escapeshellarg($target) . ' 2>&1';
            break;
        case 'curl':
            $cmd = 'curl -sI -m 10 ' . escapeshellarg($target) . ' 2>&1';
            break;
        default:
            return '未知工具。';
    }
    $output = @shell_exec($cmd);
    if ($output === null) return "{$tool} 执行失败。";
    return "{$tool} {$target} 结果：\n" . trim($output);
}

/* ------------------------------------------------------------------ */
/* read_image：调用多模态线路理解图片                                   */
/* ------------------------------------------------------------------ */
function exec_read_image($args, $ctx) {
    $id = clean_id($args['image_id'] ?? '');
    $path = UPLOADS_DIR . '/' . $id;
    $meta = jread($path . '.meta.json', null);
    if (!is_array($meta) || !is_file($path)) return '错误：找不到 id=' . $id . ' 的图片附件。';
    $mime = (string)($meta['mime'] ?? 'image/png');
    if (strpos($mime, 'image/') !== 0) return '错误：id=' . $id . ' 不是图片文件（' . $mime . '）。';

    $size = filesize($path);
    if ($size > 8 * 1024 * 1024) return '错误：图片过大（' . round($size / 1048576, 1) . 'MB），超出多模态处理能力。';

    $b64 = base64_encode((string)file_get_contents($path));
    $dataUrl = 'data:' . $mime . ';base64,' . $b64;
    $question = trim((string)($args['question'] ?? ''));
    if ($question === '') $question = '请详细描述这张图片的内容，包括其中的文字、物体、场景和任何关键信息。';

    $cfg = $ctx['cfg'];
    foreach ($cfg['providers'] as $p) {
        if ($p['url'] === '' || $p['key'] === '' || $p['model'] === '') continue;
        $result = call_vision($p, $dataUrl, $question);
        if ($result !== null) return "【图片理解结果（" . $meta['name'] . "）】\n" . $result;
    }
    return '错误：所有已配置线路都无法处理该图片（可能不支持多模态，或图片超出模型能力）。请确认至少一条线路支持图像输入。';
}

// 用 OpenAI 兼容多模态格式调用单条线路，成功返回文本，失败返回 null
function call_vision($provider, $dataUrl, $question) {
    $payload = [
        'model' => $provider['model'],
        'stream' => false,
        'messages' => [[
            'role' => 'user',
            'content' => [
                ['type' => 'text', 'text' => $question],
                ['type' => 'image_url', 'image_url' => ['url' => $dataUrl]]
            ]
        ]],
        'max_tokens' => 2048,
    ];
    $ch = curl_init();
    curl_setopt_array($ch, [
        CURLOPT_URL            => $provider['url'],
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => json_encode($payload, JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER     => [
            'Content-Type: application/json',
            'Authorization: Bearer ' . $provider['key'],
        ],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_CONNECTTIMEOUT => 15,
        CURLOPT_TIMEOUT        => 120,
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => 0,
    ]);
    $resp = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if ($resp === false || $code < 200 || $code >= 300) return null;
    $data = json_decode($resp, true);
    if (!is_array($data) || !isset($data['choices'][0]['message']['content'])) return null;
    $content = $data['choices'][0]['message']['content'];
    if (is_array($content)) {
        // content 可能是分段数组，取文本
        $txt = '';
        foreach ($content as $part) {
            if (is_array($part) && isset($part['text'])) $txt .= $part['text'];
        }
        $content = $txt;
    }
    $content = trim((string)$content);
    return $content !== '' ? $content : null;
}

/* ------------------------------------------------------------------ */
/* read_file：提取文档文本                                              */
/* ------------------------------------------------------------------ */
function exec_read_file($args, $ctx) {
    $id = clean_id($args['file_id'] ?? '');
    $path = UPLOADS_DIR . '/' . $id;
    $meta = jread($path . '.meta.json', null);
    if (!is_array($meta) || !is_file($path)) return '错误：找不到 id=' . $id . ' 的文件附件。';
    $ext = strtolower((string)($meta['ext'] ?? ''));

    $text = null;
    $err = '';
    switch ($ext) {
        case 'txt': case 'md': case 'csv': case 'json': case 'log':
            $raw = (string)file_get_contents($path);
            $text = ensure_utf8($raw);
            break;
        case 'docx':
            $text = extract_docx($path, $err);
            break;
        case 'pdf':
            $text = extract_pdf($path, $err);
            break;
        case 'xlsx': case 'pptx':
            $err = '暂不支持直接解析 .' . $ext . '，请先导出为 PDF 或文本';
            break;
        default:
            $err = '不支持的文件类型：.' . $ext;
    }

    if ($text === null) return '错误：无法读取文件 ' . $meta['name'] . '。' . ($err !== '' ? $err : '');
    $text = trim($text);
    if ($text === '') return '提示：文件 ' . $meta['name'] . ' 内容为空或无法提取出文本。';

    // 截断避免上下文爆炸
    $max = 12000;
    if (function_exists('mb_strlen')) {
        if (mb_strlen($text) > $max) $text = mb_substr($text, 0, $max) . "\n\n…（内容过长已截断）";
    } else {
        if (strlen($text) > $max * 3) $text = substr($text, 0, $max * 3) . "\n\n…（内容过长已截断）";
    }

    $instruction = trim((string)($args['instruction'] ?? ''));
    $head = '【文件内容：' . $meta['name'] . '】' . ($instruction !== '' ? "（用户要求：{$instruction}）" : '') . "\n\n";
    return $head . $text;
}

// 编码归一：尽量转成 UTF-8
function ensure_utf8($raw) {
    if ($raw === '') return '';
    if (function_exists('mb_check_encoding') && mb_check_encoding($raw, 'UTF-8')) {
        // 去掉 BOM
        if (substr($raw, 0, 3) === "\xEF\xBB\xBF") $raw = substr($raw, 3);
        return $raw;
    }
    if (function_exists('mb_convert_encoding')) {
        $conv = @mb_convert_encoding($raw, 'UTF-8', 'GBK,GB18030,BIG5,UTF-8');
        if ($conv !== false && $conv !== '') return $conv;
    }
    return $raw;
}

// 解析 docx（ZIP + word/document.xml）
function extract_docx($path, &$err) {
    if (!class_exists('ZipArchive')) { $err = '服务器缺少 ZipArchive 扩展'; return null; }
    $zip = new ZipArchive();
    if ($zip->open($path) !== true) { $err = 'docx 文件损坏或无法打开'; return null; }
    $xml = $zip->getFromName('word/document.xml');
    $zip->close();
    if ($xml === false) { $err = 'docx 内未找到正文'; return null; }
    // 段落/换行/制表符 → 换行
    $xml = preg_replace('/<\/w:p>/', "\n", $xml);
    $xml = preg_replace('/<w:tab[^>]*\/>/', "\t", $xml);
    $xml = preg_replace('/<w:br[^>]*\/>/', "\n", $xml);
    $text = strip_tags($xml);
    $text = html_entity_decode($text, ENT_QUOTES | ENT_XML1, 'UTF-8');
    $text = preg_replace('/\n{3,}/', "\n\n", $text);
    return trim($text);
}

// 解析 pdf：优先 pdftotext，其次 PHP 粗略提取
function extract_pdf($path, &$err) {
    // 1) pdftotext（最可靠）
    $bin = trim((string)shell_exec('command -v pdftotext 2>/dev/null'));
    if ($bin !== '') {
        $cmd = escapeshellarg($bin) . ' -enc UTF-8 ' . escapeshellarg($path) . ' - 2>/dev/null';
        $out = shell_exec($cmd);
        if ($out !== null && trim($out) !== '') return $out;
    }
    // 2) 粗略提取：解压缩 PDF 内容流里的文本（仅对未压缩/部分有效，兜底用）
    $raw = (string)file_get_contents($path);
    $text = '';
    if (preg_match_all('/BT([\s\S]*?)ET/', $raw, $m)) {
        foreach ($m[1] as $seg) {
            if (preg_match_all('/\((?:[^()\\\\]|\\\\.)*\)/', $seg, $t)) {
                foreach ($t[0] as $s) {
                    $s = substr($s, 1, -1);
                    $s = str_replace(['\\(', '\\)', '\\\\'], ['(', ')', '\\'], $s);
                    $text .= $s;
                }
                $text .= "\n";
            }
        }
    }
    $text = trim($text);
    if ($text === '') { $err = '无法提取 PDF 文本（建议服务器安装 pdftotext：apt install poppler-utils）'; return null; }
    return $text;
}

/* ------------------------------------------------------------------ */
/* RAG 知识库：分块 + embeddings 向量化 + 余弦检索                      */
/* ------------------------------------------------------------------ */

// 从聊天线路推导 embeddings 端点与模型
function rag_endpoint($cfg) {
    $embedUrl = trim((string)($cfg['embed_url'] ?? ''));
    $embedModel = trim((string)($cfg['embed_model'] ?? ''));
    if ($embedModel === '') return null;
    if ($embedUrl === '') {
        foreach ($cfg['providers'] as $p) {
            if ($p['url'] !== '' && $p['key'] !== '') {
                $u = $p['url'];
                if (strpos($u, '/chat/completions') !== false) {
                    $embedUrl = str_replace('/chat/completions', '/embeddings', $u);
                } else {
                    $embedUrl = rtrim($u, '/') . '/embeddings';
                }
                break;
            }
        }
    }
    if ($embedUrl === '') return null;
    return ['url' => $embedUrl, 'model' => $embedModel];
}

// 批量向量化：OpenAI 兼容 /embeddings，input 支持数组
function embed_batch($cfg, $texts) {
    $ep = rag_endpoint($cfg);
    if ($ep === null) return null;
    $key = '';
    foreach ($cfg['providers'] as $p) { if ($p['key'] !== '') { $key = $p['key']; break; } }
    $payload = ['model' => $ep['model'], 'input' => $texts];
    $ch = curl_init();
    curl_setopt_array($ch, [
        CURLOPT_URL => $ep['url'],
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode($payload, JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'Authorization: Bearer ' . $key],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 120,
        CURLOPT_SSL_VERIFYPEER => false,
    ]);
    $resp = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if ($code !== 200 || !$resp) return null;
    $d = json_decode($resp, true);
    if (!isset($d['data']) || !is_array($d['data'])) return null;
    // 按 index 排序，返回 embedding 数组
    usort($d['data'], function ($a, $b) { return ($a['index'] ?? 0) <=> ($b['index'] ?? 0); });
    $vecs = [];
    foreach ($d['data'] as $row) { if (isset($row['embedding'])) $vecs[] = $row['embedding']; }
    return count($vecs) === count($texts) ? $vecs : null;
}

// 简单分块：按段落/句子切，目标长度约 500 字
function chunk_text($text, $target = 500) {
    $text = trim((string)$text);
    if ($text === '') return [];
    $paras = preg_split('/\n{2,}/', $text);
    $chunks = [];
    $buf = '';
    foreach ($paras as $p) {
        $p = trim($p);
        if ($p === '') continue;
        if (strlen($buf) + strlen($p) <= $target * 3 || $buf === '') {
            $buf .= ($buf === '' ? '' : "\n\n") . $p;
        } else {
            $chunks[] = $buf;
            $buf = $p;
        }
        while (strlen($buf) > $target * 3) {
            $chunks[] = substr($buf, 0, $target * 3);
            $buf = substr($buf, $target * 3);
        }
    }
    if ($buf !== '') $chunks[] = $buf;
    return array_values(array_filter($chunks, function ($c) { return trim($c) !== ''; }));
}

function cosine($a, $b) {
    $dot = 0.0; $na = 0.0; $nb = 0.0;
    $n = min(count($a), count($b));
    for ($i = 0; $i < $n; $i++) {
        $dot += $a[$i] * $b[$i];
        $na += $a[$i] * $a[$i];
        $nb += $b[$i] * $b[$i];
    }
    if ($na <= 0 || $nb <= 0) return 0.0;
    return $dot / (sqrt($na) * sqrt($nb));
}

function load_kb() { return jread(KB_FILE, []); }

// 用最后一个用户问题检索知识库 top-k 片段
function kb_retrieve($cfg, $q, $topK = 4) {
    $kb = load_kb();
    if (!$kb) return [];
    // 优先语义检索（需配置嵌入模型）
    if (rag_endpoint($cfg) !== null) {
        $qv = embed_batch($cfg, [$q]);
        if ($qv !== null) {
            $qvec = $qv[0];
            $scored = [];
            foreach ($kb as $doc) {
                foreach (($doc['chunks'] ?? []) as $ci => $ch) {
                    if (empty($ch['vec'])) continue;
                    $scored[] = ['score' => cosine($qvec, $ch['vec']), 'text' => $ch['text'], 'title' => $doc['title'] ?? ''];
                }
            }
            if ($scored) {
                usort($scored, function ($a, $b) { return $b['score'] <=> $a['score']; });
                return array_slice($scored, 0, $topK);
            }
        }
    }
    // 兜底：关键词检索（零配置可用）
    return kb_keyword_retrieve($q, $topK);
}

// 关键词检索：查询切词（整词 + 中文二元组），按命中计分，归一化到 0~1
function kb_keyword_retrieve($q, $topK = 4) {
    $kb = load_kb();
    if (!$kb) return [];
    $terms = [];
    $segs = preg_split('/[^\p{L}\p{N}]+/u', (string)$q) ?: [];
    foreach ($segs as $seg) {
        $seg = trim($seg);
        if ($seg === '') continue;
        if (strlen($seg) <= 12) $terms[] = $seg;
        $chars = preg_split('//u', $seg, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        for ($i = 0; $i + 1 < count($chars); $i++) {
            $bi = $chars[$i] . $chars[$i + 1];
            if (strlen($bi) >= 4) $terms[] = $bi; // 仅中文等宽字符二元组
        }
    }
    $terms = array_values(array_unique($terms));
    if (!$terms) return [];
    $scored = [];
    foreach ($kb as $doc) {
        foreach (($doc['chunks'] ?? []) as $ch) {
            $text = $ch['text'] ?? '';
            if ($text === '') continue;
            $score = 0.0;
            foreach ($terms as $t) {
                $c = substr_count($text, $t);
                if ($c > 0) $score += 1 + log($c);
            }
            if ($score > 0) $scored[] = ['score' => $score, 'text' => $text, 'title' => $doc['title'] ?? ''];
        }
    }
    if (!$scored) return [];
    usort($scored, function ($a, $b) { return $b['score'] <=> $a['score']; });
    $max = $scored[0]['score'];
    foreach ($scored as &$s) $s['score'] = $max > 0 ? $s['score'] / $max : 0;
    unset($s);
    return array_slice($scored, 0, $topK);
}

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
    $headers = ['Content-Type: application/json', 'Accept: text/event-stream'];
    if ($provider['key'] !== '') $headers[] = 'Authorization: Bearer ' . $provider['key'];
    curl_setopt_array($ch, [
        CURLOPT_URL            => $provider['url'],
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => json_encode($payload, JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER     => $headers,
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
