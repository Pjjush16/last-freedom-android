<?php
/**
 * PHP 内置服务器路由脚本
 * 职责：封禁 /data/ 目录与隐藏文件（内含密钥、会话、上传文件），其余走默认行为。
 * 生产环境用 Nginx 时同样要封禁 /data/（见 README）。
 */
$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';

// 封禁数据目录和一切隐藏文件/目录
if (preg_match('#^/data(/|$)#', $path) || preg_match('#(^|/)\.#', $path)) {
    http_response_code(403);
    header('Content-Type: text/plain; charset=utf-8');
    echo '403 Forbidden';
    exit;
}

// 根路径 → 前端页面
if ($path === '/') {
    header('Content-Type: text/html; charset=utf-8');
    readfile(__DIR__ . '/index.html');
    exit;
}

// 其他请求（api.php、soundfonts/ 等静态资源）交给内置服务器默认处理
return false;
