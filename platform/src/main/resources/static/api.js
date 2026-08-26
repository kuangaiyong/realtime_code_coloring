/**
 * 后端调用的薄封装。
 *
 * 只做一件事：把「服务端说算不出可信结果」（4xx，响应体里有 error）
 * 变成一个带原话的异常。平台的 4xx 从来都附带一句说明是为什么判不了，
 * 把它丢掉换成「HTTP 409」，页面上就只剩一个数字，人无从下手。
 */

async function request(path, options) {
  let r;
  try {
    r = await fetch(path, options);
  } catch (e) {
    // 网络层失败（平台没起、被防火墙挡了）没有响应体，得自己造一句话
    throw new Error('连不上平台：' + e.message);
  }
  let body = null;
  try {
    body = await r.json();
  } catch (e) {
    if (r.ok) throw new Error('平台返回的不是 JSON（HTTP ' + r.status + '）');
  }
  if (!r.ok) {
    const err = new Error((body && body.error) || ('HTTP ' + r.status));
    err.status = r.status;
    err.body = body;
    throw err;
  }
  return body;
}

export const api = {
  get: (path) => request(path),
  del: (path) => request(path, { method: 'DELETE' }),
  put: (path, body) => request(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }),
  post: (path, body) => request(path, {
    method: 'POST',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? '' : JSON.stringify(body)
  })
};

/**
 * 引号也必须转义：场景 ID 由用户自取，会被拼进属性值，
 * 少转一个引号就能从属性里逃出来挂上事件处理器。
 *
 * Vue 的模板插值本身会转义，这个函数只给少数几处 v-html 用 ——
 * 那几处要输出我们自己写的 <b>/<code>，没法交给插值。
 */
export function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/**
 * 同事多半是从 http://内网IP:18090 打开这个平台的，而非 localhost。
 * 非 https 的源上 navigator.clipboard 压根不存在 —— 只用它的话，
 * 复制按钮对多数人是废的。所以退回 execCommand：它早已废弃，
 * 但不挑安全上下文，正是这里需要的。
 */
export async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (e) { /* 落到下面的兜底 */ }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.cssText = 'position:fixed;left:-9999px;top:0';
  document.body.appendChild(ta);
  ta.select();
  let done = false;
  try { done = document.execCommand('copy'); } catch (e) { done = false; }
  document.body.removeChild(ta);
  return done;
}

/** 覆盖率的三档配色。70/45 这两条线在染色、排行、实例表里必须一致 */
export function pctClass(r) {
  return r >= 70 ? 's' : r >= 45 ? 'w' : 'd';
}

export const LANG = { java: 'Java', go: 'Go', cpp: 'C++', rust: 'Rust' };
