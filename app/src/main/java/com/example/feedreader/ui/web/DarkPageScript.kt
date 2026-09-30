package com.example.feedreader.ui.web

/**
 * 生成网页暗色的注入脚本（纯字符串拼接，不碰 Android 类型，所以能在 JVM 单测里跑）。
 *
 * 一份脚本同时做两件事，因为**它必须自己按宿主判断该用哪套**：document-start 阶段
 * Kotlin 侧还不知道这次导航落到哪个域（站内跳转会换 host），而站点专项表又只能在
 * 首屏之前生效才不闪白。
 *
 * 暴露 `window.__mfDark.early()` / `.smart(confident)` 两个入口给
 * [DarkPageInjector] 在导航回调里再调一次：SPA 站内换路由时文档不重建，
 * 只有回调能补上；老 WebView 没有 document-start 能力时也靠这两个调用兜底。
 */
object DarkPageScript {

    /** 注入用的完整脚本。幂等，每次导航重复执行无副作用。 */
    fun build(
        rules: List<SiteRule> = SiteDarkStyles.RULES,
        invertCss: String = SiteDarkStyles.INVERT_CSS,
        minValidSamples: Int = SiteDarkStyles.MIN_VALID_SAMPLES,
        darkLuminanceThreshold: Double = SiteDarkStyles.DARK_LUMINANCE_THRESHOLD,
    ): String {
        val rulesJson = rules.joinToString(",", "[", "]", transform = ::ruleAsJson)
        return TEMPLATE
            .replace("@@RULES@@", rulesJson)
            .replace("@@INVERT@@", jsString(invertCss))
            .replace("@@MIN_SAMPLES@@", minValidSamples.toString())
            .replace("@@DARK_LUM@@", darkLuminanceThreshold.toString())
    }

    private fun ruleAsJson(rule: SiteRule): String {
        val hosts = rule.hosts.joinToString(",", "[", "]", transform = ::jsString)
        val attrs = rule.rootAttributes.entries.joinToString(",", "{", "}") {
            "${jsString(it.key)}:${jsString(it.value)}"
        }
        return "{\"hosts\":$hosts,\"css\":${jsString(rule.css)},\"attrs\":$attrs}"
    }

    /**
     * 转成 JS 字符串字面量（含首尾引号）。
     *
     * `\u2028` / `\u2029` 在 JS 里是**行终止符**：直接塞进 `<script>` 会把字符串截断成
     * 语法错误，而且只在中文站点里出问题，最难查。JSON 的转义形式刚好也是合法 JS。
     */
    fun jsString(raw: String): String {
        val out = StringBuilder(raw.length + 8).append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    // U+2028 / U+2029 是 JS 的行终止符：原样写进脚本会把这个字符串
                    // 截断成语法错误，而且只在中文文案里出现，最难查。
                    if (ch.code == 0x2028 || ch.code == 0x2029) out.append("\\u").append(ch.code.toString(16))
                    else out.append(ch)
                }
            }
        }
        return out.append('"').toString()
    }

    private const val TEMPLATE = """
(function () {
  if (window.self !== window.top) return;
  /* 只管 http(s)：没链接的条目会在本地渲染一份摘要页（data:），它自带配色，
     被套上反相只会更难看；about:blank 同理。 */
  if (location.protocol !== 'https:' && location.protocol !== 'http:') return;
  if (window.__mfDark) return;

  var INVERT_ID = 'mf-dark';
  var SITE_ID = 'mf-site';
  var INVERT_CSS = @@INVERT@@;
  var RULES = @@RULES@@;

  function host() { return (location.hostname || '').toLowerCase(); }

  /* 只认精确域名和它的子域：weibo.com.evil.test 这种拼后缀的不能命中。 */
  function hits(list) {
    var h = host();
    for (var i = 0; i < list.length; i++) {
      var d = String(list[i]).toLowerCase();
      if (h === d || h.slice(0 - d.length - 1) === '.' + d) return true;
    }
    return false;
  }

  function rule() {
    for (var i = 0; i < RULES.length; i++) { if (hits(RULES[i].hosts)) return RULES[i]; }
    return null;
  }

  function styleNode(id) {
    var s = document.getElementById(id);
    if (s) return s;
    var root = document.documentElement;
    if (!root) return null;
    s = document.createElement('style');
    s.id = id;
    root.appendChild(s);
    return s;
  }

  function drop(id) {
    var s = document.getElementById(id);
    if (s) s.remove();
  }

  function applySite(r) {
    var root = document.documentElement;
    var s = styleNode(SITE_ID);
    if (!root || !s) return false;
    s.textContent = r.css;
    for (var k in r.attrs) {
      if (Object.prototype.hasOwnProperty.call(r.attrs, k)) root.setAttribute(k, r.attrs[k]);
    }
    drop(INVERT_ID);
    return true;
  }

  function applyInvert() {
    var s = styleNode(INVERT_ID);
    if (!s) return false;
    s.textContent = INVERT_CSS;
    drop(SITE_ID);
    return true;
  }

  function cssHasDark() {
    try {
      var root = document.documentElement;
      var body = document.body || root;
      var cs = (getComputedStyle(root).colorScheme || '') + ' ' +
               (getComputedStyle(body).colorScheme || '');
      return cs.toLowerCase().indexOf('dark') !== -1;
    } catch (e) { return false; }
  }

  function parseRgb(c) {
    if (!c || c === 'transparent' || c === 'rgba(0, 0, 0, 0)') return null;
    var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(c);
    if (!m) return null;
    var a = m[4] === undefined ? 1 : parseFloat(m[4]);
    if (a <= 0) return null;
    return [parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10)];
  }

  function luminance(r, g, b) {
    function f(v) {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  }

  /* 网格采样可见区背景亮度。加载到一半时 DOM 是空的，采不到实色是常态，
     所以判不出来时（confident=false）返回 false：宁可保持反相，也不中途把白底放出来。 */
  function pageIsAlreadyDark(confident) {
    try {
      var pts = [0.08, 0.25, 0.5, 0.75, 0.92];
      var sum = 0, cnt = 0;
      for (var yi = 0; yi < pts.length; yi++) {
        for (var xi = 0; xi < pts.length; xi++) {
          var x = Math.min(window.innerWidth - 1, Math.max(0, Math.floor(window.innerWidth * pts[xi])));
          var y = Math.min(window.innerHeight - 1, Math.max(0, Math.floor(window.innerHeight * pts[yi])));
          var el = document.elementFromPoint(x, y);
          var rgb = null;
          while (el && !rgb) {
            rgb = parseRgb(getComputedStyle(el).backgroundColor);
            el = el.parentElement;
          }
          if (!rgb) continue;
          sum += luminance(rgb[0], rgb[1], rgb[2]);
          cnt++;
        }
      }
      if (cnt < @@MIN_SAMPLES@@) return confident;
      return (sum / cnt) < @@DARK_LUM@@;
    } catch (e) { return confident; }
  }

  var api = {
    early: function () {
      var r = rule();
      return r ? applySite(r) : applyInvert();
    },
    smart: function (confident) {
      var r = rule();
      if (r) return applySite(r) ? 'site' : 'no-root';
      if (cssHasDark()) { drop(INVERT_ID); return 'site-declared-dark'; }
      if (pageIsAlreadyDark(confident)) { drop(INVERT_ID); return 'page-dark'; }
      return applyInvert() ? 'inverted' : 'no-root';
    }
  };
  window.__mfDark = api;

  if (!api.early()) {
    /* documentElement 还没出现：它一出现就立刻补上样式。 */
    var mo = new MutationObserver(function () { if (api.early()) mo.disconnect(); });
    mo.observe(document, { childList: true, subtree: true });
  }
  document.addEventListener('DOMContentLoaded', function () {
    try { api.smart(false); } catch (e) {}
  });
})();
"""
}
