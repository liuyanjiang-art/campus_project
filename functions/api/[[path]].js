/* 把 /api/* 的全部请求代理到后端（见 functions/_proxy.js）
   [[path]] 为 catch-all 路由，可匹配任意深度的子路径，不消耗多余路由名额 */

import { proxyRequest } from '../_proxy.js';

export async function onRequest(context) {
    return proxyRequest(context);
}
