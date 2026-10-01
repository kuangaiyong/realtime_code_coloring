/**
 * 源码逐行语法高亮的分词。
 *
 * <b>只分词，不产出 HTML</b>：拿 Prism.tokenize 的结构化 token，按行切成 {type, text} 交给组件用
 * 文本插值渲染 —— 源码里再多 &lt; 也只会被当成文本，不存在注入。用 Prism.highlight 拿 HTML 再 v-html
 * 的话，高亮对了也等于在页面上开了一个口子。
 *
 * <b>跨行 token 要把类型带到下一行</b>：源码按行渲染（每行一个 DOM 行，染色挂在行上），
 * 而块注释、多行字符串是一个 token 跨好几行；不带过去，第二行开始就被当成代码上色。
 *
 * <b>任何意外都退回纯文本</b>：语言不认识、Prism 没加载、分词抛错 —— 高亮只是可读性，
 * 染色（data-status）不经过这里，所以退化成单色也不会让覆盖数据出错。
 */

/** 扩展名 → Prism 语言名。只收平台支持的四种语言会产生的源文件 */
const LANG = {
  java: 'java',
  go: 'go',
  c: 'c',
  h: 'cpp', hh: 'cpp', hpp: 'cpp', cc: 'cpp', cpp: 'cpp', cxx: 'cpp',
  rs: 'rust'
};

/**
 * 取语法定义，取不到就是 null。<b>不能只判 window.Prism 存在</b>：index.html 在加载 prism-core 之前
 * 就设了 window.Prism = { manual: true }，prism-core 没加载上（漏拷了文件、被网关拦了）时它照样是真值，
 * 只是没有 languages —— 直接读会抛错，连带 openFile 失败、整个染色页打不开
 */
function grammarOf(lang) {
  const P = window.Prism;
  return (lang && P && P.languages && P.languages[lang]) || null;
}

export function langOf(path) {
  const m = /\.([A-Za-z0-9]+)$/.exec(String(path || ''));
  const lang = m ? LANG[m[1].toLowerCase()] : null;
  return grammarOf(lang) ? lang : null;
}

function plain(text) {
  return String(text).split('\n').map(t => (t ? [{ type: '', text: t }] : []));
}

/**
 * 按行分词。返回的行数恒等于 text.split('\n').length；每行是一组 {type, text}，
 * type 为空串表示普通文本，否则是空格分隔的 token 类型（外层在前，含别名），
 * 渲染时逐个加上 tk- 前缀当 class。空行是空数组。
 */
export function tokenizeLines(text, lang) {
  const src = String(text);
  const grammar = grammarOf(lang);
  if (!grammar) return plain(src);
  let tokens;
  try {
    tokens = window.Prism.tokenize(src, grammar);
  } catch (e) {
    return plain(src);
  }
  const lines = [[]];
  const emit = (type, str) => {
    const parts = str.split('\n');
    for (let i = 0; i < parts.length; i++) {
      if (i > 0) lines.push([]);
      if (parts[i]) lines[lines.length - 1].push({ type, text: parts[i] });
    }
  };
  const walk = (node, type) => {
    if (typeof node === 'string') {
      emit(type, node);
    } else if (Array.isArray(node)) {
      for (const n of node) walk(n, type);
    } else if (node) {
      const own = [node.type].concat(node.alias || []).join(' ');
      walk(node.content, type ? type + ' ' + own : own);
    }
  };
  walk(tokens, '');
  return lines;
}
