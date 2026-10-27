/* 把 /uploads/* 代理到后端（头像、活动封面等本地上传的图片）
   若不代理，这些图片会因存在于后端而非 Pages 上而 404 */

import { proxyRequest } from '../_proxy.js';

export async function onRequest(context) {
    return proxyRequest(context);
}
