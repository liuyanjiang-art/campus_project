/* ================================================================
   Pages Functions 共享代理逻辑
   ----------------------------------------------------------------
   把 /api/* 与 /uploads/* 的请求转发到 Ktor 后端（Cloudflare 隧道域名）。
   浏览器侧只看到 Pages 域名，因此「同源」，不存在跨域问题。

   上游地址通过 Pages 项目的环境变量 UPSTREAM_ORIGIN 注入：
     Settings → Environment variables → UPSTREAM_ORIGIN = https://xxx.trycloudflare.com
   隧道域名变化时只需改这个变量，前端代码无需改动、也无需重新打包。
   ================================================================ */

/** 需要剔除的请求头：这些由 Cloudflare 注入，转发到上游会干扰后端判断 */
const STRIP_HEADERS = [
    'host',
    'cf-connecting-ip',
    'cf-ipcountry',
    'cf-ray',
    'cf-visitor',
    'cf-ew-via',
    'cdn-loop',
    'x-forwarded-proto',
    'x-real-ip',
    'x-forwarded-host'
];

export async function proxyRequest(context) {
    const { request, env } = context;
    const url = new URL(request.url);

    const upstream = String((env && env.UPSTREAM_ORIGIN) || '').replace(/\/+$/, '');
    if (!upstream) {
        return json({ error: '服务端未配置 UPSTREAM_ORIGIN 环境变量' }, 500);
    }

    const headers = new Headers(request.headers);
    for (const name of STRIP_HEADERS) headers.delete(name);

    const hasBody = request.method !== 'GET' && request.method !== 'HEAD';

    try {
        return await fetch(upstream + url.pathname + url.search, {
            method: request.method,
            headers,
            body: hasBody ? request.body : undefined,
            redirect: 'manual'
        });
    } catch (e) {
        return json({ error: '无法连接后端服务：' + (e && e.message ? e.message : e) }, 502);
    }
}

function json(obj, status) {
    return new Response(JSON.stringify(obj), {
        status: status || 200,
        headers: { 'Content-Type': 'application/json; charset=utf-8' }
    });
}
