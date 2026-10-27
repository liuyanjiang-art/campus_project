/* ================================================================
 *  西南交通大学 校园社团活动发布与报名 App —— 前端单页应用
 *  纯原生 JS，无构建步骤；通过 /api 与 Ktor 后端通信。
 * ================================================================ */

/* 拆分部署（前端在 Cloudflare Pages、后端在隧道域名）时由 config.js 注入后端地址；
   留空则回退同源 /api，本地开发与「Functions 同源代理」部署均不受影响 */
const CAMPUS_BACKEND = (window.CAMPUS_CONFIG && window.CAMPUS_CONFIG.backend
    ? String(window.CAMPUS_CONFIG.backend) : '').replace(/\/+$/, '');
const API = CAMPUS_BACKEND + '/api';
const LS_TOKEN = 'campus.token';
const LS_USER = 'campus.user';

const ROLE_TEXT = { STUDENT: '学生', ORGANIZER: '社团组织者', TEACHER: '社团管理老师' };
const STATUS_TEXT = {
    PENDING_REVIEW: '待审核',
    PUBLISHED: '已发布',
    REJECTED: '审核未通过',
    CANCELLED: '已取消'
};

const IMAGE_OPTIONS = [
    { label: '音乐 / 演出（默认）', value: '/images/music.svg' },
    { label: '编程 / 竞赛', value: '/images/code.svg' },
    { label: '体育 / 球类', value: '/images/basketball.svg' },
    { label: '阅读 / 分享会', value: '/images/reading.svg' },
    { label: '摄影 / 采风', value: '/images/photography.svg' },
    { label: '校园风景', value: '/images/campus.svg' }
];

const state = {
    token: localStorage.getItem(LS_TOKEN) || '',
    user: readJSON(localStorage.getItem(LS_USER)),
    toastTimer: null,
    activities: [],
    rendering: false
};

/* ---------------------------- 工具函数 ---------------------------- */

function readJSON(text) {
    try {
        return text ? JSON.parse(text) : null;
    } catch (e) {
        return null;
    }
}

function esc(value) {
    return String(value === undefined || value === null ? '' : value).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}

function app() {
    return document.getElementById('app');
}

/** 后端托管的资源（/api/... 与 /uploads/...）需拼接后端域名；前端本地静态资源原样返回 */
function assetUrl(u) {
    if (!u) return u;
    return CAMPUS_BACKEND && (u.indexOf('/uploads/') === 0 || u.indexOf('/api/') === 0)
        ? CAMPUS_BACKEND + u
        : u;
}

function setSession(token, user) {
    state.token = token || '';
    state.user = user || null;
    if (token) localStorage.setItem(LS_TOKEN, token);
    else localStorage.removeItem(LS_TOKEN);
    if (user) localStorage.setItem(LS_USER, JSON.stringify(user));
    else localStorage.removeItem(LS_USER);
}

function toast(message, type) {
    const el = document.getElementById('toast');
    el.textContent = message;
    el.className = 'toast show ' + (type || 'info');
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(function () { el.className = 'toast'; }, 3000);
}

/** 统一的后端请求封装，自动附带 token 并把错误转成异常 */
async function api(path, options) {
    const opt = options || {};
    const method = opt.method || 'GET';
    const headers = {};
    if (opt.body !== undefined) headers['Content-Type'] = 'application/json';
    if (opt.auth !== false && state.token) headers['Authorization'] = 'Bearer ' + state.token;

    let res;
    try {
        res = await fetch(API + path, {
            method: method,
            headers: headers,
            body: opt.body === undefined ? undefined : JSON.stringify(opt.body)
        });
    } catch (e) {
        throw new Error('网络连接失败，请确认后端服务已启动');
    }

    const text = await res.text();
    let data = readJSON(text);
    if (data === null) data = text;

    if (!res.ok) {
        if (res.status === 401) setSession('', null);
        const msg = (data && data.error) ? data.error : ('请求失败（' + res.status + '）');
        throw new Error(msg);
    }
    return data;
}

/** 上传图片（multipart/form-data），返回后端保存后的本地地址 */
async function uploadImage(file) {
    const form = new FormData();
    form.append('file', file, file.name);
    let res;
    try {
        res = await fetch(API + '/uploads', {
            method: 'POST',
            headers: state.token ? { 'Authorization': 'Bearer ' + state.token } : {},
            body: form
        });
    } catch (e) {
        throw new Error('图片上传失败，请确认后端服务已启动');
    }
    const text = await res.text();
    let data = readJSON(text);
    if (!res.ok) {
        if (res.status === 401) setSession('', null);
        throw new Error((data && data.error) ? data.error : '图片上传失败');
    }
    return data.url;
}

/** 从本地选择图片并上传，回调返回图片地址 */
function pickImage(onPicked) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.addEventListener('change', async function () {
        const file = input.files && input.files[0];
        if (!file) return;
        try {
            toast('图片上传中…', 'info');
            const url = await uploadImage(file);
            toast('图片上传成功', 'success');
            onPicked(url);
        } catch (e) {
            toast(e.message, 'error');
        }
    });
    input.click();
}

function toInputValue(dt) {
    return String(dt || '').replace(' ', 'T').slice(0, 16);
}

function todayInput() {
    const d = new Date(Date.now() + 86400000);
    return d.toISOString().slice(0, 16);
}

function statusBadge(status) {
    return '<span class="status ' + status + '">' + (STATUS_TEXT[status] || status) + '</span>';
}

function coverHtml(activity) {
    return '<div class="cover">' +
        '<span>' + esc(activity.clubName || '社团活动') + '</span>' +
        '<img src="' + esc(assetUrl(activity.imageUrl) || '/images/campus.svg') + '" alt="" loading="lazy" onerror="this.remove()">' +
        '</div>';
}

/** 头像：已上传则显示图片，否则显示姓名首字 */
function avatarHtml(user) {
    if (user && user.avatarUrl) {
        return '<img class="avatar-img" src="' + esc(assetUrl(user.avatarUrl)) + '" alt="头像" onerror="this.remove()">';
    }
    const initial = (user && user.name ? user.name : '?').trim().charAt(0);
    return '<span class="avatar-fallback">' + esc(initial) + '</span>';
}

function loading(text) {
    return '<div class="loading"><div class="spinner"></div><p>' + esc(text || '加载中…') + '</p></div>';
}

function emptyState(text, hint) {
    return '<div class="empty"><div class="empty-icon">🗓</div><p>' + esc(text) + '</p>' +
        (hint ? '<p class="muted">' + esc(hint) + '</p>' : '') + '</div>';
}

function expiryText(view) {
    if (view.activity.status !== 'PUBLISHED') return '该活动当前不可报名';
    if (view.myRegistrationId) return '你已报名该活动';
    if (view.remaining <= 0) return '名额已满';
    if (!view.registrationOpen) return '报名已截止';
    return '';
}

/* ---------------------------- 路由 ---------------------------- */

function currentRoute() {
    const raw = location.hash.replace(/^#\/?/, '');
    const parts = raw.split('/').filter(Boolean);
    return { name: parts[0] || '', param: parts[1] || '' };
}

function go(hash) {
    location.hash = hash;
}

function defaultRoute() {
    if (!state.user) return '#/login';
    if (state.user.role === 'TEACHER') return '#/review';
    if (state.user.role === 'ORGANIZER') return '#/organizer';
    return '#/activities';
}

function navItems(role) {
    const items = [];
    if (role === 'TEACHER') items.push({ key: 'review', label: '活动审核', icon: '📋' });
    items.push({ key: 'activities', label: '活动广场', icon: '🎪' });
    if (role === 'ORGANIZER') items.push({ key: 'organizer', label: '我的发布', icon: '🛠' });
    if (role === 'STUDENT') items.push({ key: 'mine', label: '我的报名', icon: '🎫' });
    return items;
}

/** 页面外壳：顶部栏 + 导航 + 内容区 */
function shell(activeKey, contentHtml) {
    const user = state.user;
    const nav = navItems(user.role).map(function (item) {
        return '<a class="nav-item ' + (item.key === activeKey ? 'active' : '') + '" href="#/' + item.key + '">' +
            '<span class="nav-icon">' + item.icon + '</span>' + esc(item.label) + '</a>';
    }).join('');

    const subtitle = [ROLE_TEXT[user.role], user.clubName || user.college].filter(Boolean).join(' · ');

    return '' +
        '<header class="appbar">' +
        '  <div class="brand">' +
        '    <div class="logo">SWJTU</div>' +
        '    <div><h1>校园社团活动</h1><p>' + esc(user.name) + ' · ' + esc(subtitle) + '</p></div>' +
        '  </div>' +
        '  <div class="appbar-right">' +
        '    <button class="avatar-btn" id="avatarBtn" title="点击上传 / 更换头像">' + avatarHtml(user) + '</button>' +
        '    <button class="btn ghost sm" id="logoutBtn">退出</button>' +
        '  </div>' +
        '</header>' +
        '<nav class="tabbar">' + nav + '</nav>' +
        '<main class="content">' + contentHtml + '</main>';
}

function bindShell() {
    const btn = document.getElementById('logoutBtn');
    if (btn) {
        btn.addEventListener('click', async function () {
            try { await api('/auth/logout', { method: 'POST', auth: false }); } catch (e) { /* 忽略 */ }
            setSession('', null);
            go('#/login');
        });
    }

    const avatarBtn = document.getElementById('avatarBtn');
    if (avatarBtn) {
        avatarBtn.addEventListener('click', function () {
            pickImage(async function (url) {
                try {
                    const updated = await api('/auth/avatar', { method: 'POST', body: { avatarUrl: url } });
                    setSession(state.token, updated);
                    toast('头像已更新', 'success');
                    render();
                } catch (e) {
                    toast(e.message, 'error');
                }
            });
        });
    }
}

/* ============================ 视图：登录 ============================ */

function renderLogin() {
    app().innerHTML = '' +
        '<div class="login-page">' +
        '  <div class="login-hero">' +
        '    <div class="hero-badge">西 南 交 通 大 学</div>' +
        '    <h1>校园社团活动<br>发布与报名平台</h1>' +
        '    <p>活动发布 · 扫码报名 · 教师审核 · 活动提醒</p>' +
        '  </div>' +
        '  <div class="login-card">' +
        '    <div class="tabs">' +
        '      <button class="tab active" data-mode="login">登录</button>' +
        '      <button class="tab" data-mode="register">注册新账号</button>' +
        '    </div>' +

        '    <form id="loginForm" class="form">' +
        '      <label>学号 / 工号<input id="username" required placeholder="例如 20210001"></label>' +
        '      <label>密码<input id="password" type="password" required placeholder="例如 123456"></label>' +
        '      <button class="btn primary block" type="submit">登录</button>' +
        '    </form>' +

        '    <form id="registerForm" class="form hidden">' +
        '      <label>账号（学号 / 工号）<input id="rUsername" required placeholder="例如 20210003"></label>' +
        '      <label>姓名<input id="rName" required placeholder="请输入真实姓名"></label>' +
        '      <label>身份' +
        '        <select id="rRole">' +
        '          <option value="STUDENT">学生</option>' +
        '          <option value="ORGANIZER">社团组织者</option>' +
        '        </select>' +
        '      </label>' +
        '      <label id="clubWrap" class="hidden">所属社团<input id="rClub" placeholder="例如 吉他社"></label>' +
        '      <div class="form-row">' +
        '        <label>学院<input id="rCollege" placeholder="选填"></label>' +
        '        <label>联系电话<input id="rPhone" placeholder="选填"></label>' +
        '      </div>' +
        '      <label>密码（至少 6 位）<input id="rPassword" type="password" required placeholder="至少 6 位"></label>' +
        '      <button class="btn accent block" type="submit">注册并登录</button>' +
        '    </form>' +

        '    <div class="demo">' +
        '      <p class="muted">演示账号（密码均为 123456）</p>' +
        '      <div class="chips">' +
        '        <button class="chip" data-u="20210001">学生 20210001</button>' +
        '        <button class="chip" data-u="guitarclub">组织者 guitarclub</button>' +
        '        <button class="chip" data-u="teacher01">老师 teacher01</button>' +
        '      </div>' +
        '    </div>' +
        '  </div>' +
        '</div>';

    const loginForm = document.getElementById('loginForm');
    const registerForm = document.getElementById('registerForm');

    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (tab) {
        tab.addEventListener('click', function () {
            const isLogin = tab.dataset.mode === 'login';
            document.querySelectorAll('.tab').forEach(function (t) { t.classList.toggle('active', t === tab); });
            loginForm.classList.toggle('hidden', !isLogin);
            registerForm.classList.toggle('hidden', isLogin);
        });
    });

    document.getElementById('rRole').addEventListener('change', function (e) {
        document.getElementById('clubWrap').classList.toggle('hidden', e.target.value !== 'ORGANIZER');
    });

    loginForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        const btn = loginForm.querySelector('button[type=submit]');
        btn.disabled = true;
        try {
            const res = await api('/auth/login', {
                method: 'POST',
                auth: false,
                body: {
                    username: document.getElementById('username').value.trim(),
                    password: document.getElementById('password').value
                }
            });
            setSession(res.token, res.user);
            toast('欢迎回来，' + res.user.name, 'success');
            afterLogin();
        } catch (err) {
            toast(err.message, 'error');
        } finally {
            btn.disabled = false;
        }
    });

    registerForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        const btn = registerForm.querySelector('button[type=submit]');
        btn.disabled = true;
        try {
            const res = await api('/auth/register', {
                method: 'POST',
                auth: false,
                body: {
                    username: document.getElementById('rUsername').value.trim(),
                    name: document.getElementById('rName').value.trim(),
                    role: document.getElementById('rRole').value,
                    clubName: document.getElementById('rClub').value.trim(),
                    college: document.getElementById('rCollege').value.trim(),
                    phone: document.getElementById('rPhone').value.trim(),
                    password: document.getElementById('rPassword').value
                }
            });
            setSession(res.token, res.user);
            toast('注册成功，已自动登录', 'success');
            afterLogin();
        } catch (err) {
            toast(err.message, 'error');
        } finally {
            btn.disabled = false;
        }
    });

    Array.prototype.forEach.call(document.querySelectorAll('.chip'), function (chip) {
        chip.addEventListener('click', function () {
            document.getElementById('username').value = chip.dataset.u;
            document.getElementById('password').value = '123456';
            loginForm.dispatchEvent(new Event('submit'));
        });
    });
}

/** 登录成功后：优先跳回扫码链接，否则跳到角色首页 */
function afterLogin() {
    const pending = sessionStorage.getItem('pendingScan');
    if (pending) {
        sessionStorage.removeItem('pendingScan');
        go('#/scan/' + pending);
    } else {
        go(defaultRoute());
    }
}

/* ============================ 视图：活动广场 ============================ */

async function renderActivities() {
    app().innerHTML = shell('activities',
        '<div class="page-head"><h2>活动广场</h2><p id="countText">加载中…</p></div>' +
        '<div class="filters">' +
        '  <input class="input" id="kw" placeholder="搜索活动名称 / 社团 / 地点">' +
        '  <select id="statusFilter">' +
        '    <option value="">全部状态</option>' +
        '    <option value="PUBLISHED">已发布</option>' +
        '    <option value="PENDING_REVIEW">待审核</option>' +
        '    <option value="REJECTED">审核未通过</option>' +
        '    <option value="CANCELLED">已取消</option>' +
        '  </select>' +
        '</div>' +
        '<div id="list">' + loading() + '</div>');
    bindShell();

    try {
        state.activities = await api('/activities');
    } catch (e) {
        document.getElementById('list').innerHTML = '<div class="notice err">' + esc(e.message) + '</div>';
        return;
    }

    if (state.user.role === 'STUDENT') {
        document.getElementById('statusFilter').classList.add('hidden');
    }

    function paint() {
        const kw = document.getElementById('kw').value.trim().toLowerCase();
        const st = document.getElementById('statusFilter').value;
        const list = state.activities.filter(function (v) {
            const a = v.activity;
            const hitKw = !kw || [a.title, a.clubName, a.location, a.content]
                .join(' ').toLowerCase().indexOf(kw) >= 0;
            return hitKw && (!st || a.status === st);
        });
        document.getElementById('countText').textContent = '共 ' + list.length + ' 个活动';
        document.getElementById('list').innerHTML = list.length
            ? '<div class="card-grid">' + list.map(activityCard).join('') + '</div>'
            : emptyState('没有找到符合条件的活动', '试试更换关键词或筛选条件');
        Array.prototype.forEach.call(document.querySelectorAll('[data-goto]'), function (el) {
            el.addEventListener('click', function () { go('#/activity/' + el.dataset.goto); });
        });
    }

    document.getElementById('kw').addEventListener('input', paint);
    document.getElementById('statusFilter').addEventListener('change', paint);
    paint();
}

function activityCard(view) {
    const a = view.activity;
    const percent = a.capacity > 0 ? Math.min(100, Math.round(view.registeredCount / a.capacity * 100)) : 0;
    let badge;
    if (view.myRegistrationId && view.myRegistrationStatus === 'REGISTERED') {
        badge = '<span class="badge ok">已报名</span>';
    } else if (view.registrationOpen) {
        badge = '<span class="badge live">报名中</span>';
    } else {
        badge = '<span class="badge off">已关闭</span>';
    }

    return '' +
        '<article class="card activity-card" data-goto="' + a.id + '">' +
        coverHtml(a) +
        '  <div class="body">' +
        '    <div class="row"><span class="tag">' + esc(a.clubName) + '</span>' +
        (state.user.role === 'STUDENT' ? badge : statusBadge(a.status)) + '</div>' +
        '    <h3>' + esc(a.title) + '</h3>' +
        '    <p class="meta">🕒 ' + esc(a.startTime) + ' 开始</p>' +
        '    <p class="meta">📍 ' + esc(a.location) + '</p>' +
        '    <div class="progress"><i style="width:' + percent + '%"></i></div>' +
        '    <div class="foot-row">' +
        '      <span class="muted">已报名 ' + view.registeredCount + ' / ' + a.capacity + ' 人</span>' +
        '      <span class="muted">截止 ' + esc(a.registrationDeadline) + '</span>' +
        '    </div>' +
        '  </div>' +
        '</article>';
}

/* ============================ 视图：活动详情 ============================ */

async function renderActivityDetail(id) {
    app().innerHTML = shell('activities', loading());
    bindShell();

    let view;
    try {
        view = await api('/activities/' + id);
    } catch (e) {
        app().innerHTML = shell('activities', '<div class="notice err">' + esc(e.message) + '</div>');
        bindShell();
        return;
    }

    const a = view.activity;
    const user = state.user;
    const mine = view.myRegistrationId && view.myRegistrationStatus === 'REGISTERED';
    let actionHtml = '';

    if (user.role === 'STUDENT') {
        if (mine) {
            actionHtml = '<div class="notice ok">✅ 你已成功报名，报名编号 ' + esc(view.myRegistrationId) + '</div>' +
                '<button class="btn danger block" id="cancelSignup">取消报名</button>' +
                '<a class="btn block" href="#/mine" style="display:block;text-align:center;margin-top:8px;background:#e8eef5;">前往「我的报名」设置提醒</a>';
        } else if (view.activity.status === 'PUBLISHED' && view.registrationOpen) {
            actionHtml = '<button class="btn primary block" id="signupBtn">立即报名</button>';
        } else {
            actionHtml = '<div class="notice warn">' + esc(expiryText(view)) + '</div>';
        }
    }

    if ((user.role === 'ORGANIZER' && a.organizerId === user.id) || user.role === 'TEACHER') {
        actionHtml += '<a class="btn block" href="#/manage/' + a.id + '" style="display:block;text-align:center;margin-top:8px;">进入活动管理</a>';
    }

    app().innerHTML = shell('activities', '' +
        '<a class="muted" href="#/activities">‹ 返回活动广场</a>' +
        '<div class="card" style="margin-top:10px;">' +
        coverHtml(a) +
        '  <div class="detail-head">' +
        '    <div class="row"><span class="tag">' + esc(a.clubName) + '</span>' +
        (user.role === 'STUDENT' ? '' : statusBadge(a.status)) + '</div>' +
        '    <h2>' + esc(a.title) + '</h2>' +
        '    <div class="row wrap">' +
        '      <span class="badge live">已报名 ' + view.registeredCount + ' 人</span>' +
        '      <span class="badge ' + (view.registrationOpen ? 'ok' : 'off') + '">剩余 ' + view.remaining + ' 个名额</span>' +
        '    </div>' +
        '    <div class="info-grid">' +
        infoItem('活动时间', a.startTime + ' ~ ' + a.endTime) +
        infoItem('活动地点', a.location) +
        infoItem('报名截止', a.registrationDeadline) +
        infoItem('参与要求', a.requirements || '无特殊要求') +
        infoItem('主办社团', a.clubName + '（' + a.organizerName + '）') +
        infoItem('报名编号', '扫码或点击报名后自动生成') +
        '    </div>' +
        (a.status === 'REJECTED' && a.reviewComment
            ? '<div class="notice err" style="margin-top:12px;">审核意见：' + esc(a.reviewComment) + '</div>' : '') +
        '    <div style="margin-top:16px;">' + actionHtml + '</div>' +
        '  </div>' +
        '</div>' +

        '<div class="section-title">活动介绍</div>' +
        '<div class="card card-pad"><p class="para">' + esc(a.content) + '</p></div>' +

        (((user.role === 'ORGANIZER' && a.organizerId === user.id) || user.role === 'TEACHER') ?
            '<div class="section-title">报名二维码</div>' +
            '<div class="card qr-box">' +
            '  <img src="' + esc(assetUrl('/api/activities/' + a.id + '/qrcode.png')) + '?base=' + encodeURIComponent(location.origin) + '" alt="报名二维码">' +
            '  <div class="qr-code">' + esc(a.qrToken) + '</div>' +
            '  <p class="muted">学生使用手机扫码即可直接进入报名页</p>' +
            '  <p class="qr-url">' + esc(location.origin + '/#/scan/' + a.qrToken) + '</p>' +
            '</div>' : ''));
    bindShell();

    const signupBtn = document.getElementById('signupBtn');
    if (signupBtn) {
        signupBtn.addEventListener('click', async function () {
            signupBtn.disabled = true;
            try {
                await api('/registrations', { method: 'POST', body: { activityId: a.id } });
                toast('报名成功！', 'success');
                renderActivityDetail(id);
            } catch (e) {
                toast(e.message, 'error');
                signupBtn.disabled = false;
            }
        });
    }

    const cancelBtn = document.getElementById('cancelSignup');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', async function () {
            if (!confirm('确定要取消报名吗？')) return;
            try {
                await api('/registrations/' + view.myRegistrationId, { method: 'DELETE' });
                toast('已取消报名', 'success');
                renderActivityDetail(id);
            } catch (e) {
                toast(e.message, 'error');
            }
        });
    }
}

function infoItem(key, value) {
    return '<div class="info-item"><span class="k">' + esc(key) + '</span><span class="v">' + esc(value) + '</span></div>';
}

/* ============================ 视图：扫码报名 ============================ */

async function renderScan(token) {
    app().innerHTML = shell('activities', loading('正在识别二维码…'));
    bindShell();

    let view;
    try {
        view = await api('/scan/' + token, { auth: false });
    } catch (e) {
        app().innerHTML = shell('activities', '<div class="notice err">' + esc(e.message) + '</div>');
        bindShell();
        return;
    }

    const a = view.activity;
    const student = state.user.role === 'STUDENT';
    let attempted = false;
    let message = '';

    // 学生扫码后自动为其报名
    if (student && view.registrationOpen && !view.myRegistrationId) {
        attempted = true;
        try {
            await api('/registrations', { method: 'POST', body: { qrToken: token } });
            message = '<div class="notice ok">✅ 报名成功！活动信息已加入「我的报名」。</div>';
        } catch (e) {
            message = '<div class="notice err">' + esc(e.message) + '</div>';
        }
    } else if (view.myRegistrationId && view.myRegistrationStatus === 'REGISTERED') {
        message = '<div class="notice info">你此前已经报名过该活动。</div>';
    } else if (!student) {
        message = '<div class="notice info">当前身份为「' + esc(ROLE_TEXT[state.user.role]) + '」，仅学生可报名。</div>';
    } else {
        message = '<div class="notice warn">' + esc(expiryText(view)) + '</div>';
    }

    if (attempted) view = await api('/scan/' + token, { auth: false });

    app().innerHTML = shell('activities', '' +
        '<div class="page-head"><h2>扫码报名</h2><p>二维码令牌 ' + esc(token) + '</p></div>' +
        message +
        '<div class="card">' + coverHtml(a) +
        '  <div class="detail-head">' +
        '    <span class="tag">' + esc(a.clubName) + '</span>' +
        '    <h2 style="font-size:18px;margin:8px 0;">' + esc(a.title) + '</h2>' +
        '    <div class="info-grid">' +
        infoItem('活动时间', a.startTime + ' ~ ' + a.endTime) +
        infoItem('活动地点', a.location) +
        infoItem('报名截止', a.registrationDeadline) +
        '    </div>' +
        '    <div style="margin-top:14px;display:flex;gap:8px;">' +
        '      <a class="btn primary block" href="#/activity/' + a.id + '" style="text-align:center;">查看活动详情</a>' +
        '      <a class="btn block" href="#/mine" style="text-align:center;">我的报名</a>' +
        '    </div>' +
        '  </div>' +
        '</div>');
    bindShell();
}

/* ============================ 视图：我的报名 ============================ */

async function renderMine() {
    app().innerHTML = shell('mine', loading());
    bindShell();

    let registrations;
    let reminders;
    try {
        registrations = await api('/registrations/mine');
        reminders = await api('/reminders/mine');
    } catch (e) {
        app().innerHTML = shell('mine', '<div class="notice err">' + esc(e.message) + '</div>');
        bindShell();
        return;
    }

    const reminderMap = {};
    reminders.forEach(function (r) { reminderMap[r.activityId] = r; });

    const active = registrations.filter(function (r) { return r.registration.status === 'REGISTERED'; });
    const cancelled = registrations.filter(function (r) { return r.registration.status !== 'REGISTERED'; });

    const listHtml = active.length ? active.map(function (item) {
        return registrationCard(item, reminderMap[item.registration.activityId]);
    }).join('') : emptyState('还没有报名任何活动', '去「活动广场」看看有什么活动吧');

    const cancelledHtml = cancelled.length
        ? '<div class="section-title">已取消的报名</div>' + cancelled.map(function (item) {
            return registrationCard(item, null, true);
        }).join('')
        : '';

    app().innerHTML = shell('mine', '' +
        '<div class="page-head"><h2>我的报名</h2><p>有效报名 ' + active.length + ' 个 · 已取消 ' + cancelled.length + ' 个 · 提醒 ' + reminders.length + ' 条</p></div>' +
        reminders.length ? '<div class="section-title">已设置的提醒</div>' + reminders.map(reminderCard).join('') : '' +
        '<div class="section-title">报名记录</div>' + listHtml + cancelledHtml);
    bindShell();

    Array.prototype.forEach.call(document.querySelectorAll('.js-cancel-reg'), function (btn) {
        btn.addEventListener('click', async function () {
            if (!confirm('确定取消该活动的报名吗？')) return;
            try {
                await api('/registrations/' + btn.dataset.id, { method: 'DELETE' });
                toast('已取消报名', 'success');
                renderMine();
            } catch (e) {
                toast(e.message, 'error');
            }
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.js-reminder-form'), function (form) {
        form.addEventListener('submit', async function (e) {
            e.preventDefault();
            try {
                await api('/reminders', {
                    method: 'POST',
                    body: {
                        activityId: form.dataset.activity,
                        remindAt: form.querySelector('[name=remindAt]').value,
                        note: form.querySelector('[name=note]').value.trim()
                    }
                });
                toast('提醒设置成功', 'success');
                renderMine();
            } catch (err) {
                toast(err.message, 'error');
            }
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.js-del-reminder'), function (btn) {
        btn.addEventListener('click', async function () {
            try {
                await api('/reminders/' + btn.dataset.id, { method: 'DELETE' });
                toast('已删除提醒', 'success');
                renderMine();
            } catch (e) {
                toast(e.message, 'error');
            }
        });
    });
}

function registrationCard(item, reminder, cancelled) {
    const reg = item.registration;
    const a = item.activity;
    if (!a) {
        return '<div class="card card-pad"><p>' + esc(reg.activityId) + '（活动已被删除）</p></div>';
    }
    const startTime = toInputValue(a.startTime);
    const defaultRemind = startTime || todayInput();

    const reminderBlock = cancelled ? '' : (reminder
        ? '<div class="notice info" style="margin-top:10px;">⏰ 已设置提醒：' + esc(reminder.remindAt) +
        (reminder.note ? '（' + esc(reminder.note) + '）' : '') + '</div>'
        : '<details class="reminder-box">' +
        '  <summary>为该活动设置提醒</summary>' +
        '  <form class="form js-reminder-form" data-activity="' + a.id + '" style="margin-top:8px;">' +
        '    <label>提醒时间<input type="datetime-local" name="remindAt" value="' + esc(defaultRemind) + '" required></label>' +
        '    <label>备注<input name="note" placeholder="例如：记得带琴"></label>' +
        '    <button class="btn sm primary" type="submit">保存提醒</button>' +
        '  </form>' +
        '</details>');

    return '' +
        '<div class="card reg-card' + (cancelled ? ' is-cancelled' : '') + '">' +
        coverHtml(a) +
        '  <div class="card-pad">' +
        '  <div class="row">' +
        '    <span class="tag">' + esc(a.clubName) + '</span>' +
        (cancelled ? '<span class="status CANCELLED">已取消报名</span>' : statusBadge(a.status)) +
        '  </div>' +
        '  <h3 class="reg-title">' + esc(a.title) + '</h3>' +
        '  <div class="info-grid">' +
        infoItem('活动时间', a.startTime + ' ~ ' + a.endTime) +
        infoItem('活动地点', a.location) +
        infoItem('报名截止', a.registrationDeadline) +
        infoItem('参与要求', a.requirements || '无特殊要求') +
        infoItem('主办社团', a.clubName + '（' + a.organizerName + '）') +
        infoItem('报名编号', reg.id) +
        infoItem('报名时间', reg.registeredAt) +
        infoItem('报名状态', cancelled ? '已取消' : '报名成功') +
        '  </div>' +
        (a.status === 'CANCELLED' && !cancelled ? '<div class="notice warn" style="margin-top:12px;">主办方已取消该活动</div>' : '') +
        (cancelled ? '<div class="notice info" style="margin-top:12px;">你已取消该活动的报名，此记录仍保留以便随时查看。</div>' : '') +
        '<div class="section-title">活动介绍</div>' +
        '<p class="para">' + esc(a.content) + '</p>' +
        reminderBlock +
        '  <div class="row" style="margin-top:12px;gap:8px;">' +
        '    <a class="btn sm" href="#/activity/' + a.id + '" style="flex:1;text-align:center;">查看详情</a>' +
        (cancelled ? '' : '<button class="btn sm danger js-cancel-reg" data-id="' + reg.id + '" style="flex:1;">取消报名</button>') +
        '  </div>' +
        '  </div>' +
        '</div>';
}

function reminderCard(reminder) {
    return '' +
        '<div class="card card-pad">' +
        '  <div class="row">' +
        '    <b>' + esc(reminder.activityTitle) + '</b>' +
        '    <button class="btn sm danger js-del-reminder" data-id="' + reminder.id + '">删除</button>' +
        '  </div>' +
        '  <p class="meta">⏰ ' + esc(reminder.remindAt) + (reminder.note ? ' · ' + esc(reminder.note) : '') + '</p>' +
        '</div>';
}

/* ============================ 视图：我的发布 ============================ */

async function renderOrganizerHome() {
    app().innerHTML = shell('organizer', loading());
    bindShell();

    let list;
    let stats;
    try {
        list = await api('/activities?mine=true');
        stats = await api('/stats');
    } catch (e) {
        app().innerHTML = shell('organizer', '<div class="notice err">' + esc(e.message) + '</div>');
        bindShell();
        return;
    }

    app().innerHTML = shell('organizer', '' +
        '<div class="page-head"><h2>我的发布</h2><p>' + esc(state.user.clubName || '') + '</p></div>' +
        '<div class="stat-grid">' +
        '  <div class="stat"><b>' + stats.myActivities + '</b><span>我发布的活动</span></div>' +
        '  <div class="stat"><b>' + stats.published + '</b><span>全校已发布</span></div>' +
        '  <div class="stat"><b>' + stats.pending + '</b><span>待审核</span></div>' +
        '</div>' +
        '<div style="margin:14px 0;"><a class="btn primary block" href="#/organizer/new" style="display:block;text-align:center;">＋ 发布新活动</a></div>' +
        (list.length ? '<div class="card-grid">' + list.map(organizerCard).join('') + '</div>'
            : emptyState('你还没有发布过活动', '点击上方按钮发布第一个活动')));
    bindShell();

    Array.prototype.forEach.call(document.querySelectorAll('[data-goto]'), function (el) {
        el.addEventListener('click', function () { go('#/manage/' + el.dataset.goto); });
    });
}

function organizerCard(view) {
    const a = view.activity;
    return '' +
        '<article class="card activity-card" data-goto="' + a.id + '">' +
        coverHtml(a) +
        '  <div class="body">' +
        '    <div class="row"><span class="tag">' + esc(a.clubName) + '</span>' + statusBadge(a.status) + '</div>' +
        '    <h3>' + esc(a.title) + '</h3>' +
        '    <p class="meta">🕒 ' + esc(a.startTime) + '</p>' +
        '    <p class="meta">📍 ' + esc(a.location) + '</p>' +
        '    <div class="foot-row">' +
        '      <span class="muted">已报名 ' + view.registeredCount + ' / ' + a.capacity + ' 人</span>' +
        '      <span class="muted">截止 ' + esc(a.registrationDeadline) + '</span>' +
        '    </div>' +
        (a.status === 'REJECTED' && a.reviewComment
            ? '<div class="notice err" style="margin-top:10px;">' + esc(a.reviewComment) + '</div>' : '') +
        '  </div>' +
        '</article>';
}

/* ============================ 视图：发布 / 编辑活动 ============================ */

function activityFormHtml(activity) {
    const a = activity || {};
    const currentImage = a.imageUrl || IMAGE_OPTIONS[0].value;
    const imageOptions = IMAGE_OPTIONS.map(function (opt) {
        return '<option value="' + opt.value + '"' + (currentImage === opt.value ? ' selected' : '') + '>' + esc(opt.label) + '</option>';
    }).join('');
    const customSelected = IMAGE_OPTIONS.every(function (opt) { return opt.value !== currentImage; });

    return '' +
        '<form id="activityForm" class="form card card-pad">' +
        '  <label>活动名称<input name="title" required value="' + esc(a.title || '') + '" placeholder="例如：吉他社迎新音乐会"></label>' +
        '  <label>活动介绍<textarea name="content" required placeholder="介绍活动内容、流程、亮点…">' + esc(a.content || '') + '</textarea></label>' +
        '  <label>活动地点<input name="location" required value="' + esc(a.location || '') + '" placeholder="例如：犀浦校区 大学生活动中心 报告厅"></label>' +
        '  <label>参与要求<input name="requirements" value="' + esc(a.requirements || '') + '" placeholder="例如：在校学生，自备乐器"></label>' +
        '  <div class="form-row">' +
        '    <label>人数上限<input name="capacity" type="number" min="1" required value="' + esc(a.capacity || 50) + '"></label>' +
        '    <label>封面图片（预设）<select id="coverPreset">' +
        (customSelected ? '<option value="__custom__" selected>自定义上传的图片</option>' : '') +
        imageOptions + '</select></label>' +
        '  </div>' +
        '  <input type="hidden" name="imageUrl" id="imageUrlInput" value="' + esc(currentImage) + '">' +
        '  <div class="cover-picker">' +
        '    <div class="cover-preview"><img id="coverPreview" src="' + esc(currentImage) + '" alt="封面预览" onerror="this.remove()"></div>' +
        '    <button type="button" class="btn accent block" id="coverUploadBtn">📷 上传自定义封面图片</button>' +
        '    <p class="muted center">支持 png / jpg / gif / webp，不超过 3MB</p>' +
        '  </div>' +
        '  <label>报名截止时间<input name="registrationDeadline" type="datetime-local" required value="' + esc(toInputValue(a.registrationDeadline) || todayInput()) + '"></label>' +
        '  <div class="form-row">' +
        '    <label>活动开始<input name="startTime" type="datetime-local" required value="' + esc(toInputValue(a.startTime) || todayInput()) + '"></label>' +
        '    <label>活动结束<input name="endTime" type="datetime-local" required value="' + esc(toInputValue(a.endTime) || todayInput()) + '"></label>' +
        '  </div>' +
        '  <div class="notice info">活动提交后状态为「待审核」，需社团管理老师审核通过后学生才能看到并报名。</div>' +
        '  <div class="row" style="gap:8px;margin-top:6px;">' +
        '    <button class="btn primary" type="submit" style="flex:1;">保存</button>' +
        '    <a class="btn" href="#/organizer" style="flex:1;text-align:center;">返回</a>' +
        '  </div>' +
        '</form>';
}

/** 绑定封面选择：预设下拉 + 自定义上传，都会写回隐藏字段并刷新预览 */
function bindCoverPicker() {
    const select = document.getElementById('coverPreset');
    const hidden = document.getElementById('imageUrlInput');
    const preview = document.getElementById('coverPreview');
    const uploadBtn = document.getElementById('coverUploadBtn');
    if (!select || !hidden) return;

    select.addEventListener('change', function () {
        if (select.value === '__custom__') return;
        hidden.value = select.value;
        if (preview) preview.src = select.value;
    });

    if (uploadBtn) {
        uploadBtn.addEventListener('click', function () {
            pickImage(function (url) {
                hidden.value = url;
                if (preview) preview.src = url;
                if (!select.querySelector('option[value="__custom__"]')) {
                    const opt = document.createElement('option');
                    opt.value = '__custom__';
                    opt.textContent = '自定义上传的图片';
                    select.insertBefore(opt, select.firstChild);
                }
                select.value = '__custom__';
            });
        });
    }
}

function readActivityForm() {
    const form = document.getElementById('activityForm');
    return {
        title: form.title.value.trim(),
        content: form.content.value.trim(),
        location: form.location.value.trim(),
        requirements: form.requirements.value.trim(),
        capacity: parseInt(form.capacity.value, 10),
        imageUrl: form.imageUrl.value,
        registrationDeadline: form.registrationDeadline.value,
        startTime: form.startTime.value,
        endTime: form.endTime.value
    };
}

async function renderActivityForm(id) {
    let activity = null;
    if (id) {
        try {
            const view = await api('/activities/' + id);
            activity = view.activity;
        } catch (e) {
            app().innerHTML = shell('organizer', '<div class="notice err">' + esc(e.message) + '</div>');
            bindShell();
            return;
        }
    }

    app().innerHTML = shell('organizer',
        '<div class="page-head"><h2>' + (id ? '编辑活动' : '发布新活动') + '</h2></div>' +
        activityFormHtml(activity));
    bindShell();
    bindCoverPicker();

    document.getElementById('activityForm').addEventListener('submit', async function (e) {
        e.preventDefault();
        const body = readActivityForm();
        if (!body.capacity || body.capacity < 1) {
            toast('人数上限必须大于 0', 'error');
            return;
        }
        try {
            const saved = id
                ? await api('/activities/' + id, { method: 'PUT', body: body })
                : await api('/activities', { method: 'POST', body: body });
            toast(id ? '活动已更新' : '活动已提交，等待老师审核', 'success');
            go('#/manage/' + saved.activity.id);
        } catch (err) {
            toast(err.message, 'error');
        }
    });
}

/* ============================ 视图：活动管理 ============================ */

async function renderManage(id) {
    app().innerHTML = shell(state.user.role === 'TEACHER' ? 'review' : 'organizer', loading());
    bindShell();

    let view;
    let registrations;
    let qr;
    try {
        view = await api('/activities/' + id);
        registrations = await api('/activities/' + id + '/registrations');
        qr = await api('/activities/' + id + '/qrcode?base=' + encodeURIComponent(location.origin));
    } catch (e) {
        app().innerHTML = shell(state.user.role === 'TEACHER' ? 'review' : 'organizer',
            '<div class="notice err">' + esc(e.message) + '</div>');
        bindShell();
        return;
    }

    const a = view.activity;
    const activeRegs = registrations.filter(function (r) { return r.status === 'REGISTERED'; });
    const isTeacher = state.user.role === 'TEACHER';

    const regRows = registrations.length ? registrations.map(function (r) {
        return '<tr>' +
            '<td>' + esc(r.studentName) + '</td>' +
            '<td>' + esc(r.studentNo) + '</td>' +
            '<td>' + esc(r.registeredAt) + '</td>' +
            '<td>' + (r.status === 'REGISTERED'
                ? '<span class="badge ok">已报名</span>'
                : '<span class="badge off">已取消</span>') + '</td>' +
            '</tr>';
    }).join('') : '<tr><td colspan="4" class="center muted">还没有人报名</td></tr>';

    app().innerHTML = shell(isTeacher ? 'review' : 'organizer', '' +
        '<a class="muted" href="#/' + (isTeacher ? 'review' : 'organizer') + '">‹ 返回列表</a>' +
        '<div class="card" style="margin-top:10px;">' + coverHtml(a) +
        '  <div class="detail-head">' +
        '    <div class="row"><span class="tag">' + esc(a.clubName) + '</span>' + statusBadge(a.status) + '</div>' +
        '    <h2 style="font-size:19px;margin:10px 0 8px;">' + esc(a.title) + '</h2>' +
        '    <div class="info-grid">' +
        infoItem('活动时间', a.startTime + ' ~ ' + a.endTime) +
        infoItem('活动地点', a.location) +
        infoItem('报名截止', a.registrationDeadline) +
        infoItem('报名情况', activeRegs.length + ' / ' + a.capacity + ' 人') +
        infoItem('活动编号', a.id) +
        '    </div>' +
        (a.reviewComment ? '<div class="notice ' + (a.status === 'REJECTED' ? 'err' : 'info') + '" style="margin-top:12px;">审核意见：' + esc(a.reviewComment) + '（' + esc(a.reviewerName) + '）</div>' : '') +
        '  </div>' +
        '</div>' +

        (isTeacher && a.status === 'PENDING_REVIEW' ?
            '<div class="section-title">教师审核</div>' +
            '<div class="card card-pad">' +
            '  <label class="muted" style="font-size:13px;">审核意见（驳回时请说明原因）</label>' +
            '  <textarea class="input" id="reviewComment" placeholder="例如：请补充活动安全预案"></textarea>' +
            '  <div class="row" style="gap:8px;margin-top:10px;">' +
            '    <button class="btn ok" id="approveBtn" style="flex:1;">通过并发布</button>' +
            '    <button class="btn danger" id="rejectBtn" style="flex:1;">驳回</button>' +
            '  </div>' +
            '</div>' : '') +

        '<div class="section-title">报名二维码</div>' +
        '<div class="card qr-box">' +
        '  <img src="' + esc(assetUrl(qr.imageUrl)) + '?base=' + encodeURIComponent(location.origin) + '" alt="报名二维码">' +
        '  <div class="qr-code">' + esc(qr.qrToken) + '</div>' +
        '  <p class="qr-url">' + esc(qr.url) + '</p>' +
        '  <p class="muted">打印或投屏该二维码，学生扫码即可直接报名</p>' +
        '</div>' +

        '<div class="section-title">报名名单（' + activeRegs.length + '）</div>' +
        '<div class="card table-wrap"><table class="data">' +
        '  <thead><tr><th>姓名</th><th>学号</th><th>报名时间</th><th>状态</th></tr></thead>' +
        '  <tbody>' + regRows + '</tbody>' +
        '</table></div>' +

        '<div class="section-title">修改活动信息</div>' +
        '<div class="notice info">可延长报名截止时间、增加人数上限、修改活动地点等；保存后立即生效。</div>' +
        activityFormHtml(a) +

        (a.status === 'CANCELLED' ? '' :
            '<div class="section-title">危险操作</div>' +
            '<div class="card card-pad">' +
            '  <p class="muted" style="margin-bottom:10px;">取消后活动将不再对学生展示，已报名记录仍会保留。</p>' +
            '  <button class="btn danger block" id="cancelActivityBtn">取消该活动</button>' +
            '</div>'));
    bindShell();
    bindCoverPicker();

    document.getElementById('activityForm').addEventListener('submit', async function (e) {
        e.preventDefault();
        const body = readActivityForm();
        if (!body.capacity || body.capacity < 1) {
            toast('人数上限必须大于 0', 'error');
            return;
        }
        try {
            await api('/activities/' + id, { method: 'PUT', body: body });
            toast('活动信息已更新', 'success');
            renderManage(id);
        } catch (err) {
            toast(err.message, 'error');
        }
    });

    const cancelActivityBtn = document.getElementById('cancelActivityBtn');
    if (cancelActivityBtn) {
        cancelActivityBtn.addEventListener('click', async function () {
            if (!confirm('确定取消该活动吗？此操作不可撤销。')) return;
            try {
                await api('/activities/' + id + '/cancel', { method: 'POST' });
                toast('活动已取消', 'success');
                renderManage(id);
            } catch (e) {
                toast(e.message, 'error');
            }
        });
    }

    const approveBtn = document.getElementById('approveBtn');
    if (approveBtn) {
        approveBtn.addEventListener('click', function () { review(id, true); });
        document.getElementById('rejectBtn').addEventListener('click', function () { review(id, false); });
    }
}

async function review(id, approve) {
    const comment = document.getElementById('reviewComment').value.trim();
    if (!approve && !comment) {
        toast('驳回时请填写审核意见', 'error');
        return;
    }
    try {
        await api('/activities/' + id + '/review', { method: 'POST', body: { approve: approve, comment: comment } });
        toast(approve ? '已通过审核，活动已发布' : '已驳回该活动', 'success');
        renderManage(id);
    } catch (e) {
        toast(e.message, 'error');
    }
}

/* ============================ 视图：教师审核 ============================ */

async function renderReview() {
    app().innerHTML = shell('review', loading());
    bindShell();

    let pending;
    let all;
    try {
        pending = await api('/activities?status=PENDING_REVIEW');
        all = await api('/activities');
    } catch (e) {
        app().innerHTML = shell('review', '<div class="notice err">' + esc(e.message) + '</div>');
        bindShell();
        return;
    }

    const stats = {
        pending: all.filter(function (v) { return v.activity.status === 'PENDING_REVIEW'; }).length,
        published: all.filter(function (v) { return v.activity.status === 'PUBLISHED'; }).length,
        total: all.length
    };

    app().innerHTML = shell('review', '' +
        '<div class="page-head"><h2>活动审核</h2><p>' + esc(state.user.name) + ' · 社团管理老师</p></div>' +
        '<div class="stat-grid">' +
        '  <div class="stat"><b>' + stats.pending + '</b><span>待审核</span></div>' +
        '  <div class="stat"><b>' + stats.published + '</b><span>已发布</span></div>' +
        '  <div class="stat"><b>' + stats.total + '</b><span>全部活动</span></div>' +
        '</div>' +
        '<div class="section-title">待审核活动</div>' +
        (pending.length ? pending.map(reviewCard).join('')
            : emptyState('当前没有待审核的活动', '所有活动都已处理完毕')));
    bindShell();

    Array.prototype.forEach.call(document.querySelectorAll('[data-review]'), function (btn) {
        btn.addEventListener('click', function () {
            go('#/manage/' + btn.dataset.review);
        });
    });
}

function reviewCard(view) {
    const a = view.activity;
    return '' +
        '<div class="card card-pad">' +
        '  <div class="row"><span class="tag">' + esc(a.clubName) + '</span>' + statusBadge(a.status) + '</div>' +
        '  <h3 style="font-size:16px;margin:8px 0 6px;">' + esc(a.title) + '</h3>' +
        '  <p class="para muted" style="font-size:13px;">' + esc(a.content.slice(0, 70)) + (a.content.length > 70 ? '…' : '') + '</p>' +
        '  <p class="meta">🕒 ' + esc(a.startTime) + ' ~ ' + esc(a.endTime) + '</p>' +
        '  <p class="meta">📍 ' + esc(a.location) + ' · 限 ' + a.capacity + ' 人</p>' +
        '  <p class="meta">👤 提交人 ' + esc(a.organizerName) + ' · ' + esc(a.createdAt) + '</p>' +
        '  <button class="btn primary block" data-review="' + a.id + '" style="margin-top:10px;">审核该活动</button>' +
        '</div>';
}

/* ============================ 渲染调度 ============================ */

async function render() {
    if (state.rendering) return;
    state.rendering = true;
    const r = currentRoute();
    try {
        if (!state.token || !state.user) {
            if (r.name === 'scan' && r.param) {
                sessionStorage.setItem('pendingScan', r.param);
                toast('请先登录后再扫码报名', 'info');
            }
            renderLogin();
            return;
        }
        switch (r.name) {
            case 'activities':
                await renderActivities();
                break;
            case 'activity':
                await renderActivityDetail(r.param);
                break;
            case 'scan':
                await renderScan(r.param);
                break;
            case 'mine':
                state.user.role === 'STUDENT' ? await renderMine() : go(defaultRoute());
                break;
            case 'organizer':
                if (state.user.role !== 'ORGANIZER') { go(defaultRoute()); break; }
                r.param === 'new' ? await renderActivityForm(null) : await renderOrganizerHome();
                break;
            case 'manage':
                await renderManage(r.param);
                break;
            case 'review':
                state.user.role === 'TEACHER' ? await renderReview() : go(defaultRoute());
                break;
            case 'login':
                state.token ? go(defaultRoute()) : renderLogin();
                break;
            default:
                go(defaultRoute());
        }
    } catch (e) {
        app().innerHTML = '<div class="notice err" style="margin:40px auto;max-width:520px;">' + esc(e.message) + '</div>';
    } finally {
        state.rendering = false;
    }
}

async function boot() {
    window.addEventListener('hashchange', render);

    // 校验本地保存的登录状态是否仍然有效
    if (state.token) {
        try {
            const me = await api('/auth/me');
            setSession(state.token, me);
        } catch (e) {
            setSession('', null);
        }
    }
    if (!location.hash) {
        location.replace(defaultRoute());
    } else {
        render();
    }
}

boot();
