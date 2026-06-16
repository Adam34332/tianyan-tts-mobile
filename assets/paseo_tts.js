(() => {
  if (window.__ttsBrowserInstalled) return;
  window.__ttsBrowserInstalled = true;

  const BUTTON_TEST_ID = "tts-browser-play";
  const CONTROL_ATTR = "data-tts-browser-control";
  const CHAT_SELECTORS = [
    '[data-testid="assistant-message"]',
    '[data-message-author-role="assistant"]',
    '[data-role="assistant"]',
    '[data-author="assistant"]',
    '[aria-label*="assistant" i]',
    '[aria-label*="response" i]',
    ".assistant-message",
    ".message.assistant",
    ".chat-message.assistant",
    ".bot-message",
    ".ai-message",
    ".interactive-response",
    ".interactive-item.response",
    ".chat-response",
    '[data-testid*="response" i]',
    '[class*="chat" i][class*="response" i]',
    '[class*="message" i][class*="assistant" i]',
    '[class*="assistant" i][class*="message" i]',
    '[class*="assistant" i][class*="response" i]',
  ];
  const USER_MESSAGE_SELECTORS = [
    '[data-testid="user-message"]',
    '[data-message-author-role="user"]',
    '[data-role="user"]',
    '[data-author="user"]',
    '[aria-label*="user" i]',
    '[aria-label*="you" i]',
    ".user-message",
    ".message.user",
    ".chat-message.user",
    ".human-message",
    '[class*="message" i][class*="user" i]',
    '[class*="user" i][class*="message" i]',
    '[class*="message" i][class*="human" i]',
    '[class*="human" i][class*="message" i]',
  ];
  const TEXT_SELECTORS = [
    "main p",
    "article p",
    "section p",
    ".markdown p",
    ".markdown-body p",
    ".prose p",
    "p",
    "li",
    "blockquote",
  ];
  const TOP_GAP_HOSTS = /(^|\.)paseo\.sh$|(^|\.)zcode\.z\.ai$/i;

  let activeButton = null;
  let homeReadyNotified = false;

  const setStyles = (node, styles) => {
    Object.entries(styles).forEach(([name, value]) => node.style.setProperty(name, value, "important"));
  };

  const notifyHomeReady = () => {
    if (homeReadyNotified) return;
    homeReadyNotified = true;
    try {
      if (window.TtsBrowserBridge && typeof window.TtsBrowserBridge.homeReady === "function") {
        window.TtsBrowserBridge.homeReady();
      }
    } catch {}
  };

  const closestBySelectors = (node, selectors) => {
    if (!node || !node.closest) return null;
    for (const selector of selectors) {
      try {
        const match = node.closest(selector);
        if (match) return match;
      } catch {}
    }
    return null;
  };

  const isWholePageShell = (node) => {
    if (!node || node === document.body || node === document.documentElement) return true;
    const rect = node.getBoundingClientRect();
    return rect.width >= window.innerWidth * 0.9 && rect.height >= window.innerHeight * 0.8;
  };

  const isUserAuthored = (node) => {
    const match = closestBySelectors(node, USER_MESSAGE_SELECTORS);
    return Boolean(match && !isWholePageShell(match));
  };

  const isExplicitAssistantReply = (node) => {
    if (!node || !node.matches) return false;
    try {
      return node.matches('[data-testid="assistant-message"], [data-message-author-role="assistant"], [data-role="assistant"], [data-author="assistant"]');
    } catch {
      return false;
    }
  };

  const isAssistantReply = (node) => {
    const match = closestBySelectors(node, CHAT_SELECTORS);
    return Boolean(match && match === node && (isExplicitAssistantReply(match) || !isWholePageShell(match)));
  };

  const isKnownAppShell = () => TOP_GAP_HOSTS.test(location.hostname || "");

  const isHiddenForReading = (node) => {
    if (!node || !node.closest) return true;
    return Boolean(node.closest('[hidden], [aria-hidden="true"], [inert], .sr-only, .visually-hidden, .screen-reader-only'));
  };

  const isComposerArea = (node) => {
    if (!node || !node.closest) return false;
    try {
      if (node.closest('[data-testid*="message-input" i], [data-testid*="composer" i], [data-testid*="prompt" i]')) return true;
      if (node.closest("[contenteditable='true'], [role='textbox'], form")) return true;
    } catch {}
    for (let item = node; item && item !== document.documentElement; item = item.parentElement) {
      const klass = String(item.className || "").toLowerCase();
      if (/(composer|prompt|editor|textarea)/.test(klass)) return true;
    }
    return false;
  };

  const parseRgb = (value) => {
    const match = String(value || "").match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([.\d]+))?\)/i);
    if (!match) return null;
    const alpha = match[4] === undefined ? 1 : Number(match[4]);
    if (alpha <= 0.05) return null;
    return { r: Number(match[1]), g: Number(match[2]), b: Number(match[3]) };
  };

  const isDarkSurface = (node) => {
    const candidates = [node, node.parentElement, document.body, document.documentElement].filter(Boolean);
    for (const item of candidates) {
      const rgb = parseRgb(getComputedStyle(item).backgroundColor);
      if (!rgb) continue;
      const luminance = (0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b) / 255;
      return luminance < 0.48;
    }
    return Boolean(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
  };

  const paletteFor = (node) => {
    if (isDarkSurface(node)) {
      return {
        fg: "#d8dee9",
        bg: "rgba(255,255,255,.055)",
        hover: "rgba(255,255,255,.095)",
        border: "rgba(148,163,184,.36)",
      };
    }
    return {
      fg: "#334155",
      bg: "rgba(15,23,42,.035)",
      hover: "rgba(15,23,42,.07)",
      border: "rgba(100,116,139,.34)",
    };
  };

  const fileName = (value) => {
    const clean = String(value || "").trim().replace(/[.,;:，。；：)\]}]+$/g, "");
    const base = clean.split(/[\\/]/).pop() || clean;
    const line = base.match(/:(\d+)$/);
    const withoutLine = line ? base.slice(0, -line[0].length) : base;
    const parts = withoutLine.split(".");
    const stem = (parts.length > 1 ? parts.slice(0, -1).join(".") : withoutLine).replace(/[_-]+/g, " ");
    return `文件 ${stem}${line ? `，第 ${line[1]} 行` : ""}`;
  };

  const looksCodeish = (line) => {
    const text = String(line || "").trim();
    if (text.length < 4) return false;
    if (/^(import|from|def|class|const|let|var|function|return|export|if|for|while|try:|except|curl|git|python3?|npm|pnpm)\b/.test(text)) {
      return true;
    }
    if (/(=>|==|!=|&&|\|\||<\/|\/>|[{}]|\(\);)/.test(text)) return true;
    if (/^[$%]\s/.test(text) || (text.match(/\//g) || []).length >= 4) return true;
    const ascii = (text.match(/[!-~]/g) || []).length;
    const symbols = (text.match(/[{}[\]();=<>|\\$]/g) || []).length;
    return ascii >= 20 && symbols / Math.max(ascii, 1) > 0.18;
  };

  const sanitize = (raw) => {
    let text = String(raw || "");
    text = text.replace(/```[\s\S]*?```/g, "。代码块已省略。");
    text = text.replace(/!\[[^\]]*]\([^)]*\)/g, " ");
    text = text.replace(/\[([^\]]+)]\((?:https?:\/\/|www\.)[^)]*\)/g, "$1");
    text = text.replace(/\*\*([^*\n][^*]*?)\*\*/g, "$1");
    text = text.replace(/__([^_\n][^_]*?)__/g, "$1");
    text = text.replace(/\b(?:https?:\/\/|www\.)[^\s<>)\]]+/gi, (value) => {
      const normalized = value.startsWith("http") ? value : `https://${value}`;
      try {
        const host = new URL(normalized).host.replace(/^www\./, "").replace(/\./g, " 点 ");
        return host ? `链接 ${host}` : "网页链接";
      } catch {
        return "网页链接";
      }
    });
    text = text.replace(/`([^`\n]{1,180})`/g, (_, value) => {
      const clean = value.trim();
      if (!clean) return "";
      if (/^(~|\/|[A-Za-z]:\\)/.test(clean) || /^[\w.-]+\.[A-Za-z0-9]{1,8}(:\d+)?$/.test(clean)) return fileName(clean);
      if (looksCodeish(clean) || clean.length > 60) return "代码片段已省略";
      return clean.replace(/[_-]+/g, " ");
    });
    text = text.replace(/(?<![\w/])\/([a-z][\w-]{1,24})(?=[\s，。；：、!?！？\u4e00-\u9fff]|$)/gi, "$1");
    text = text.replace(/(?<![\w:\u4e00-\u9fff])(?:~|\/|[A-Za-z]:\\)[^\s`"'<>|，。；：、!?！？]+/g, fileName);
    text = text.replace(/(?<![\w/\\.-])[\w.-]+\.(?:py|js|ts|tsx|jsx|json|md|txt|yaml|yml|toml|sh|zsh|css|html|go|rs|java|cpp|c|h|hpp|swift|kt|sql|pdf|docx?|xlsx?|pptx?|mp3|wav|m4a)(?::\d+)?/gi, fileName);
    text = text.replace(/\b[a-fA-F0-9]{24,}\b/g, "哈希值已省略");
    text = text
      .split(/\n+/)
      .map((line) => (looksCodeish(line) ? "代码已省略。" : line))
      .join("\n");
    const acronyms = {
      TTS: "语音合成",
      URL: "链接",
      URI: "链接",
      API: "接口",
      CLI: "命令行",
      UI: "界面",
      DOM: "页面结构",
      DB: "数据库",
      SQL: "数据库查询",
      PID: "进程号",
      JS: "JavaScript",
      TS: "TypeScript",
      HTML: "网页代码",
      CSS: "样式",
      VSCode: "Visual Studio Code",
    };
    Object.entries(acronyms).forEach(([key, value]) => {
      text = text.replace(new RegExp(`(?<![A-Za-z])${key}(?![A-Za-z])`, "gi"), value);
    });
    text = text.replace(/^[ \t]*[-*+]\s+/gm, "");
    text = text.replace(/[#>*_~|]+/g, " ");
    text = text.replace(/\s+/g, " ").trim();
    text = text.replace(/(代码(?:块)?已省略。?\s*){2,}/g, "代码已省略。");
    return text.slice(0, 12000);
  };

  const hasMeaningfulText = (text) => {
    const clean = sanitize(text).replace(/[\s*_~`#>|•·.,，。:：;；!?！？()[\]{}"'“”‘’-]+/g, "");
    return clean.length > 0;
  };

  const isLowContentUiSnippet = (text) => {
    const clean = sanitize(text);
    if (clean.length >= 24) return false;
    if (/[。！？!?；;，,、：:]/.test(clean)) return false;
    const cjkCount = (clean.match(/[\u4e00-\u9fff]/g) || []).length;
    if (cjkCount >= 5) return false;
    const wordCount = (clean.match(/[A-Za-z0-9]{2,}/g) || []).length;
    return wordCount <= 3;
  };

  const extractText = (node) => {
    const clone = node.cloneNode(true);
    clone.querySelectorAll(`[data-testid="${BUTTON_TEST_ID}"], [${CONTROL_ATTR}], button, textarea, input, select, script, style, noscript`).forEach((item) => item.remove());
    return sanitize(String(clone.innerText || clone.textContent || ""));
  };

  const rawElementText = (node) => String(node && (node.innerText || node.textContent) || "").replace(/\s+/g, " ").trim();

  const borderCount = (style) => (
    [style.borderTopWidth, style.borderRightWidth, style.borderBottomWidth, style.borderLeftWidth]
      .filter((value) => parseFloat(value) > 0).length
  );

  const distinctBucketCount = (values) => {
    const buckets = [];
    values.forEach((value) => {
      if (!buckets.some((item) => Math.abs(item - value) < 3)) buckets.push(value);
    });
    return buckets.length;
  };

  const looksLikeVisualTable = (node) => {
    if (!node || node === document.body || node === document.documentElement) return false;
    if (node.matches && node.matches("table, [role='table'], [role='grid']")) return true;
    const rect = node.getBoundingClientRect();
    if (rect.width < 120 || rect.height < 80) return false;
    const style = getComputedStyle(node);
    if (borderCount(style) < 2) return false;
    const cells = [...node.querySelectorAll("div, span, [role='cell'], [role='columnheader'], [role='rowheader']")]
      .slice(0, 100)
      .map((item) => {
        const itemRect = item.getBoundingClientRect();
        if (itemRect.width < 12 || itemRect.height < 12) return null;
        const itemStyle = getComputedStyle(item);
        if (borderCount(itemStyle) < 1 && itemStyle.display !== "flex" && itemStyle.display !== "grid") return null;
        return itemRect;
      })
      .filter(Boolean);
    if (cells.length < 6) return false;
    return distinctBucketCount(cells.map((item) => item.left)) >= 3
      && distinctBucketCount(cells.map((item) => item.top)) >= 2;
  };

  const visualTableFor = (node, stopAt = null) => {
    let match = null;
    for (let item = node; item && item !== document.body && item !== document.documentElement; item = item.parentElement) {
      if (item === stopAt) break;
      if (looksLikeVisualTable(item)) match = item;
    }
    return match;
  };

  const isNumberedStart = (text) => (
    /^\s*(?:\d{1,3}[.)、．]|[（(]\d{1,3}[）)]|[一二三四五六七八九十百]+[、.．])\s*/.test(text)
  );

  const isCategoryStart = (text) => {
    const clean = String(text || "").trim();
    if (!/[：:]/.test(clean)) return false;
    const head = clean.split(/[：:]/)[0].trim();
    return head.length >= 2 && head.length <= 28 && !/[。！？!?；;]/.test(head);
  };

  const isStructuralStart = (text) => isNumberedStart(text) || isCategoryStart(text);

  const isSectionHeading = (text) => {
    const clean = String(text || "").trim();
    return clean.length >= 2 && clean.length <= 40 && /^[^。！？!?；;]+[：:]$/.test(clean);
  };

  const isReadSkipNode = (node) => {
    if (!node || !node.closest) return true;
    if (node.closest(`[data-testid="${BUTTON_TEST_ID}"], [${CONTROL_ATTR}], [data-tts-browser-top-gap-trim="1"]`)) return true;
    if (isHiddenForReading(node)) return true;
    if (node.closest("script, style, noscript, button, textarea, input, select, nav, header, footer")) return true;
    return isComposerArea(node) || isUserAuthored(node);
  };

  const isReadableElement = (node) => {
    if (!node || node === document.body || node === document.documentElement || isReadSkipNode(node)) return false;
    const style = getComputedStyle(node);
    if (style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) return false;
    const rect = node.getBoundingClientRect();
    if (rect.width < 16 || rect.height < 8) return false;
    if ((style.position === "fixed" || style.position === "sticky") && rect.top < 140) return false;
    return true;
  };

  const textRangeRect = (textNode) => {
    const range = document.createRange();
    range.selectNodeContents(textNode);
    const rect = range.getBoundingClientRect();
    range.detach();
    return rect;
  };

  const readBlockFor = (node) => {
    const table = visualTableFor(node);
    if (table) return table;
    const direct = node.closest("p, li, blockquote");
    if (direct) return direct;
    for (let item = node; item && item !== document.body && item !== document.documentElement; item = item.parentElement) {
      if (isReadSkipNode(item)) return null;
      const text = String(item.innerText || item.textContent || "").replace(/\s+/g, " ").trim();
      if (!text) continue;
      if (item.querySelector("p, li, blockquote")) continue;
      const display = getComputedStyle(item).display;
      if (/^(block|flex|grid|list-item|table|flow-root)$/i.test(display)) return item;
    }
    return node.parentElement || null;
  };

  const readableViewportTop = () => {
    const viewport = window.visualViewport;
    let top = Math.max(0, viewport ? viewport.offsetTop : 0) + 6;
    if (!isKnownAppShell()) return top;
    const gap = document.querySelector('[data-tts-browser-top-gap-trim="1"]');
    if (gap) top = Math.max(top, gap.getBoundingClientRect().bottom + 6);
    [...document.querySelectorAll("button, [role='button'], nav, header")].forEach((node) => {
      const rect = node.getBoundingClientRect();
      if (rect.top <= top + 8 && rect.bottom > top && rect.bottom < 180 && rect.width > window.innerWidth * 0.45) {
        top = rect.bottom + 6;
      }
    });
    return top;
  };

  const collectReadableBlocks = () => {
    const root = document.body || document.documentElement;
    const seen = new Set();
    const blocks = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode(textNode) {
        const text = String(textNode.nodeValue || "").replace(/\s+/g, " ").trim();
        if (text.length < 2) return NodeFilter.FILTER_REJECT;
        const parent = textNode.parentElement;
        if (!parent || isReadSkipNode(parent)) return NodeFilter.FILTER_REJECT;
        const style = getComputedStyle(parent);
        if (style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) return NodeFilter.FILTER_REJECT;
        const rect = textRangeRect(textNode);
        if (rect.width < 1 || rect.height < 1) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    let textNode;
    while ((textNode = walker.nextNode())) {
      const block = readBlockFor(textNode.parentElement);
      if (!block || seen.has(block) || !isReadableElement(block)) continue;
      const text = extractText(block);
      if (text.length < 4) continue;
      if (isLowContentUiSnippet(text)) continue;
      if (/^[\x00-\x7F\s]+$/.test(text) && text.length < 24 && !/[.!?;:,]/.test(text)) continue;
      if (text.length < 8 && !/[\u4e00-\u9fff]{3,}|[。！？!?；;，,]/.test(text)) continue;
      seen.add(block);
      blocks.push({ node: block, rect: block.getBoundingClientRect(), text });
    }
    return blocks;
  };

  const collectReplyBlocks = (reply) => {
    const seen = new Set();
    const blocks = [];
    const walker = document.createTreeWalker(reply, NodeFilter.SHOW_TEXT, {
      acceptNode(textNode) {
        const text = String(textNode.nodeValue || "").replace(/\s+/g, " ").trim();
        if (text.length < 2 || !hasMeaningfulText(text)) return NodeFilter.FILTER_REJECT;
        const parent = textNode.parentElement;
        if (!parent || !reply.contains(parent)) return NodeFilter.FILTER_REJECT;
        if (parent.closest(`[data-testid="${BUTTON_TEST_ID}"], [${CONTROL_ATTR}], button, textarea, input, select, script, style, noscript`)) return NodeFilter.FILTER_REJECT;
        const style = getComputedStyle(parent);
        if (style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    let textNode;
    while ((textNode = walker.nextNode())) {
      const block = visualTableFor(textNode.parentElement, reply) || readBlockFor(textNode.parentElement);
      if (!block || block === reply || seen.has(block) || !reply.contains(block) || !isReadableElement(block)) continue;
      const text = extractText(block);
      if (!hasMeaningfulText(text)) continue;
      if (isLowContentUiSnippet(text)) continue;
      if (text.length < 8 && !/[\u4e00-\u9fff]{3,}|[。！？!?；;，,、]/.test(text)) continue;
      seen.add(block);
      blocks.push(block);
    }
    return blocks.filter((block) => !blocks.some((other) => other !== block && other.contains(block) && rawElementText(other).length <= rawElementText(block).length + 4));
  };

  const groupReplyBlocks = (blocks) => {
    const minChars = 120;
    const maxChars = 520;
    const hardMaxChars = 760;
    const groups = [];
    let current = [];
    const textOf = (item) => item.text;
    const groupText = (group) => group.map(textOf).join(" ");
    const groupHasSectionHeading = (group) => group.some((item) => isSectionHeading(item.text));
    const groupHasNumberedItem = (group) => group.some((item) => isNumberedStart(item.text));
    const shouldJoin = (group, item) => {
      if (!group.length) return false;
      const last = group[group.length - 1];
      const joined = groupText(group);
      const nextLength = joined.length + item.text.length + 1;
      if (looksLikeVisualTable(last.node) || looksLikeVisualTable(item.node)) return false;
      if (isSectionHeading(item.text)) return false;
      if (isNumberedStart(item.text) && (groupHasSectionHeading(group) || groupHasNumberedItem(group))) return true;
      if (/[：:]$/.test(last.text)) return true;
      if (groupHasSectionHeading(group)) return true;
      if (isStructuralStart(item.text)) return joined.length < minChars && nextLength <= hardMaxChars;
      if (joined.length < minChars) return nextLength <= hardMaxChars;
      if (nextLength <= maxChars && item.text.length < minChars && !isStructuralStart(last.text)) return true;
      return nextLength <= hardMaxChars && !/[。！？!?；;]/.test(last.text);
    };
    blocks.forEach((node) => {
      const text = extractText(node);
      if (!hasMeaningfulText(text)) return;
      const item = { node, text };
      if (!current.length || shouldJoin(current, item)) {
        current.push(item);
        return;
      }
      groups.push(current);
      current = [item];
    });
    if (current.length) groups.push(current);
    return groups.map((group) => ({
      node: group[group.length - 1].node,
      text: groupText(group),
    }));
  };

  const extractPageText = () => {
    const blocks = collectReadableBlocks();
    if (!blocks.length) return "";
    const viewport = window.visualViewport;
    const top = readableViewportTop();
    const bottom = top + (viewport ? viewport.height : window.innerHeight) - 12;
    let start = blocks.findIndex((item) => item.rect.bottom >= top && item.rect.top < bottom);
    if (start < 0) start = blocks.findIndex((item) => item.rect.top >= top);
    if (start < 0) start = 0;
    const texts = [];
    const used = new Set();
    blocks.slice(start).forEach((item) => {
      if (used.has(item.text)) return;
      used.add(item.text);
      texts.push(item.text);
    });
    return sanitize(texts.join("\n"));
  };

  const resetButton = (button) => {
    if (!button) return;
    button.dataset.ttsState = "idle";
    button.textContent = button.dataset.originalText || "播放语音";
    activeButton = null;
  };

  window.__ttsBrowserSetState = (state) => {
    if (!activeButton) return;
    if (state === "playing") {
      activeButton.dataset.ttsState = "playing";
      activeButton.textContent = "停止播放";
      return;
    }
    if (state === "idle" || state === "done" || state === "error") resetButton(activeButton);
  };

  window.__ttsBrowserReadPage = () => {
    if (!window.TtsBrowserBridge) return false;
    const text = extractPageText();
    if (!text) return false;
    if (typeof window.TtsBrowserBridge.speakPage === "function") {
      return Boolean(window.TtsBrowserBridge.speakPage(text));
    }
    return Boolean(window.TtsBrowserBridge.speak(text));
  };

  window.__ttsBrowserPreviewReadPage = () => extractPageText();

  const findTextElement = (needle) => {
    return [...document.querySelectorAll("button, [role='button'], a, div, span")]
      .map((node) => ({
        node,
        text: String(node.innerText || node.textContent || "").replace(/\s+/g, " ").trim(),
      }))
      .filter((item) => item.text.includes(needle) && item.text.length <= needle.length + 12)
      .sort((a, b) => a.text.length - b.text.length)[0]?.node || null;
  };

  const homeButtonOf = (node) => (node ? node.closest("button, [role='button'], a") || node : null);

  const styleHomeAction = (button) => {
    if (!button) return;
    setStyles(button, {
      "box-sizing": "border-box",
      display: "flex",
      width: "100%",
      "max-width": "100%",
      "min-height": "44px",
      "align-items": "center",
      "justify-content": "center",
      gap: "8px",
      "text-align": "center",
      "white-space": "normal",
    });
  };

  const composerTextValue = (node) => String(node && ("value" in node ? node.value : node.innerText || node.textContent) || "").trim();

  const activeComposerInput = () => {
    const active = document.activeElement;
    if (active && active.matches && active.matches("textarea, input, [contenteditable='true'], [role='textbox']") && composerTextValue(active)) {
      return active;
    }
    return [...document.querySelectorAll("textarea, input, [contenteditable='true'], [role='textbox']")]
      .find((node) => {
        const rect = node.getBoundingClientRect();
        return rect.bottom > window.innerHeight * 0.45 && composerTextValue(node);
      }) || null;
  };

  const closestSendButton = (node) => {
    const button = node && node.closest ? node.closest("button, [role='button']") : null;
    if (!button) return null;
    const label = [
      button.getAttribute("aria-label"),
      button.getAttribute("data-testid"),
      button.getAttribute("title"),
      button.innerText,
      button.textContent,
    ].join(" ");
    if (!/(^|\s)(send|发送|提交)(\s|消息|$)/i.test(label)) return null;
    if (/(播放|语音|附件|attach|听写|voice|model|mode|选择)/i.test(label)) return null;
    return button;
  };

  const installComposerSendTouchFix = () => {
    if (window.__ttsBrowserComposerSendTouchFix) return;
    window.__ttsBrowserComposerSendTouchFix = true;
    document.addEventListener("touchstart", (event) => {
      const button = closestSendButton(event.target);
      const input = activeComposerInput();
      if (!button || !input) return;
      if (event.cancelable) event.preventDefault();
      event.stopImmediatePropagation();
      try {
        input.focus({ preventScroll: true });
      } catch {
        input.focus();
      }
      setTimeout(() => {
        if (!document.contains(button) || !composerTextValue(input)) return;
        button.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, view: window }));
      }, 0);
    }, true);
  };

  const shortUrlLabel = (url) => {
    try {
      const parsed = new URL(url);
      const path = parsed.pathname && parsed.pathname !== "/" ? parsed.pathname : "";
      const label = `${parsed.host.replace(/^www\./, "")}${path}`;
      return label.length > 44 ? `${label.slice(0, 41)}...` : label;
    } catch {
      const clean = String(url || "");
      return clean.length > 44 ? `${clean.slice(0, 41)}...` : clean;
    }
  };

  const readFavoriteItems = () => {
    if (!window.TtsBrowserBridge) return [];
    try {
      const raw = typeof window.TtsBrowserBridge.favoriteItems === "function"
        ? window.TtsBrowserBridge.favoriteItems()
        : (typeof window.TtsBrowserBridge.favoriteUrls === "function"
          ? window.TtsBrowserBridge.favoriteUrls()
          : (typeof window.TtsBrowserBridge.recentUrls === "function" ? window.TtsBrowserBridge.recentUrls() : "[]"));
      const parsed = JSON.parse(raw || "[]");
      const seen = new Set();
      return parsed.map((item) => {
        const url = typeof item === "string" ? item : item && item.url;
        const title = typeof item === "object" && item ? String(item.title || "").trim() : "";
        return { url, title };
      }).filter((item) => {
        if (typeof item.url !== "string" || !/^https?:\/\//i.test(item.url) || seen.has(item.url)) return false;
        seen.add(item.url);
        return true;
      }).slice(0, 5);
    } catch {
      return [];
    }
  };

  const shortFavoriteLabel = (favorite) => {
    const clean = String(favorite.title || "").trim();
    const label = clean || shortUrlLabel(favorite.url);
    return label.length > 44 ? `${label.slice(0, 41)}...` : label;
  };

  const createFavoriteButton = (baseButton, favorite) => {
    const url = favorite.url;
    const label = shortFavoriteLabel(favorite);
    const button = baseButton ? baseButton.cloneNode(false) : document.createElement("button");
    button.removeAttribute("id");
    button.removeAttribute("disabled");
    button.removeAttribute("data-tts-browser-scan-pair");
    button.setAttribute("data-tts-browser-favorite-url", "1");
    button.setAttribute("aria-label", `打开收藏 ${label}`);
    button.textContent = label;
    button.title = url;
    if (button.tagName === "BUTTON") button.type = "button";
    if ("disabled" in button) button.disabled = false;
    if (button.tagName === "A") button.setAttribute("href", url);
    styleHomeAction(button);
    setStyles(button, {
      "margin-top": "0",
      cursor: "pointer",
      "font-size": "14px",
    });
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (window.TtsBrowserBridge && typeof window.TtsBrowserBridge.openUrl === "function") {
        window.TtsBrowserBridge.openUrl(url);
      } else {
        location.href = url;
      }
    });
    return button;
  };

  const updateFavoriteLinks = (anchorButton) => {
    const favorites = readFavoriteItems();
    let box = document.querySelector("[data-tts-browser-favorites]");
    if (!favorites.length) {
      if (box) box.remove();
      return;
    }
    if (!anchorButton || !anchorButton.parentNode) return;
    if (!box) {
      box = document.createElement("div");
      box.setAttribute("data-tts-browser-favorites", "1");
    }
    if (box.parentNode !== anchorButton.parentNode || box.previousElementSibling !== anchorButton) {
      anchorButton.parentNode.insertBefore(box, anchorButton.nextSibling);
    }
    setStyles(box, {
      display: "flex",
      "flex-direction": "column",
      gap: "8px",
      "margin-top": "12px",
      width: "100%",
      "box-sizing": "border-box",
    });
    box.replaceChildren();
    const title = document.createElement("div");
    title.textContent = "收藏网页";
    setStyles(title, {
      color: "#475569",
      "font-size": "13px",
      "font-weight": "600",
      "line-height": "18px",
      "text-align": "left",
    });
    box.appendChild(title);
    favorites.forEach((favorite) => box.appendChild(createFavoriteButton(anchorButton, favorite)));
  };

  const brandHome = () => {
    if (location.hostname !== "app.paseo.sh") return;
    const root = document.body || document.documentElement;
    if (!root) return;
    root.querySelectorAll("*").forEach((node) => {
      node.childNodes.forEach((child) => {
        if (child.nodeType === Node.TEXT_NODE && child.nodeValue && child.nodeValue.includes("Paseo")) {
          child.nodeValue = child.nodeValue.replace(/Paseo/g, "天眼");
        }
      });
    });

    if (!document.querySelector("[data-tts-browser-home-logo]")) {
      const logo = [...document.querySelectorAll("svg, img")].find((node) => {
        const rect = node.getBoundingClientRect();
        return rect.width >= 56 && rect.height >= 56 && rect.top > 120 && rect.top < window.innerHeight * 0.65;
      });
      if (logo && logo.parentElement) {
        const holder = document.createElement("span");
        holder.setAttribute("data-tts-browser-home-logo", "1");
        holder.setAttribute("aria-label", "天眼");
        setStyles(holder, {
          display: "inline-flex",
          width: "108px",
          height: "108px",
          "align-items": "center",
          "justify-content": "center",
        });
        holder.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108" role="img" aria-label="天眼">
          <path d="M14 54C25 34 39 24 54 24s29 10 40 30C83 74 69 84 54 84S25 74 14 54Z" fill="none" stroke="#0f172a" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M38 54c0-9 7-16 16-16s16 7 16 16-7 16-16 16-16-7-16-16Z" fill="none" stroke="#166534" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M50 50h8v8h-8z" fill="#166534"/>
          <path d="M54 15v11M54 82v11M15 54h11M82 54h11" fill="none" stroke="#334155" stroke-width="4" stroke-linecap="round"/>
        </svg>`;
        logo.parentElement.insertBefore(holder, logo);
        setStyles(logo, {
          position: "absolute",
          opacity: "0",
          width: "0",
          height: "0",
          overflow: "hidden",
          "pointer-events": "none",
        });
      }
    }

    const existingScanButton = document.querySelector("[data-tts-browser-scan-pair]");
    const directText = findTextElement("直接连接");
    const pasteText = findTextElement("粘贴配对链接");
    const directButton = homeButtonOf(directText);
    const pasteButton = homeButtonOf(pasteText);
    styleHomeAction(directButton);
    styleHomeAction(pasteButton);

    let scanButton = existingScanButton;
    if (!scanButton && pasteButton) {
      scanButton = pasteButton.cloneNode(true);
      scanButton.removeAttribute("id");
      scanButton.setAttribute("data-tts-browser-scan-pair", "1");
      scanButton.setAttribute("aria-label", "扫码配对");
      scanButton.querySelectorAll("svg, img").forEach((item) => item.remove());
      scanButton.textContent = "扫码配对";
      scanButton.addEventListener("click", (event) => {
        event.preventDefault();
        event.stopPropagation();
        if (window.TtsBrowserBridge) window.TtsBrowserBridge.scanPair();
      });
      pasteButton.parentNode.insertBefore(scanButton, pasteButton.nextSibling);
    }
    if (scanButton) {
      styleHomeAction(scanButton);
      setStyles(scanButton, {
        "margin-top": "10px",
        cursor: "pointer",
      });
    }
    updateFavoriteLinks(scanButton || pasteButton || directButton);
    if (existingScanButton || directButton || pasteButton || scanButton) notifyHomeReady();
  };

  const trimEmbeddedTopGap = () => {
    if (!isKnownAppShell()) return;
    const root = document.body || document.documentElement;
    if (!root) return;
    const candidate = [...root.querySelectorAll("div")].find((node) => {
      if (node.getAttribute("data-tts-browser-top-gap-trim") === "1") return true;
      if (node.closest(`[${CONTROL_ATTR}]`)) return false;
      const rect = node.getBoundingClientRect();
      const style = getComputedStyle(node);
      const paddingTop = parseFloat(style.paddingTop) || 0;
      const text = String(node.innerText || node.textContent || "").replace(/\s+/g, " ").trim();
      return rect.top >= -2
        && rect.top <= 4
        && rect.width >= Math.min(280, window.innerWidth * 0.7)
        && rect.height >= 72
        && rect.height <= 180
        && paddingTop >= 32
        && paddingTop <= 100
        && text.length >= 2
        && text.length <= 80;
    });
    if (!candidate) return;
    const rect = candidate.getBoundingClientRect();
    const paddingTop = parseFloat(getComputedStyle(candidate).paddingTop) || 0;
    const compactHeight = Math.max(44, Math.round(rect.height - paddingTop));
    candidate.setAttribute("data-tts-browser-top-gap-trim", "1");
    setStyles(candidate, {
      "padding-top": "0px",
      "min-height": `${compactHeight}px`,
      height: `${compactHeight}px`,
    });
  };

  const speak = (node, button) => {
    const original = button.dataset.originalText || button.textContent;
    button.dataset.originalText = original;
    if (button.dataset.ttsState === "playing" || button.dataset.ttsState === "starting") {
      if (window.TtsBrowserBridge) window.TtsBrowserBridge.stop();
      resetButton(button);
      return;
    }
    const text = button.__ttsTextOverride || extractText(node);
    if (!text || !window.TtsBrowserBridge) return;
    activeButton = button;
    button.dataset.ttsState = "starting";
    button.textContent = "准备播放...";
    const ok = window.TtsBrowserBridge.speak(text);
    if (ok) {
      button.dataset.ttsState = "playing";
      button.textContent = "停止播放";
    } else {
      button.textContent = "语音不可用";
      setTimeout(() => resetButton(button), 1600);
    }
  };

  const looksLikeReply = (node, chatReply = false, textOverride = "") => {
    if (node.querySelector(`[data-testid="${BUTTON_TEST_ID}"]`)) return false;
    if (node.closest("[data-tts-browser-host='1']")) return false;
    if (node.closest(`[${CONTROL_ATTR}], button, textarea, input, select, nav, header, footer, script, style, noscript`)) return false;
    if (!isReadableElement(node)) return false;
    if (!looksLikeVisualTable(node) && visualTableFor(node)) return false;
    if (isComposerArea(node) || isUserAuthored(node)) return false;
    if (node.matches("svg, img, path, code, pre")) return false;
    const text = textOverride || extractText(node);
    if (!hasMeaningfulText(text)) return false;
    if (isLowContentUiSnippet(text)) return false;
    if (chatReply) return text.length >= 8;
    const tag = node.tagName ? node.tagName.toLowerCase() : "";
    const isTextBlock = tag === "p" || tag === "li" || tag === "blockquote";
    if (!isTextBlock && node.querySelector("p, li, blockquote")) return false;
    const minLength = isTextBlock ? 16 : 8;
    if (text.length < minLength) return false;
    if (!isTextBlock && text.length < 24 && !/[。！？!?；;，,、：:]/.test(text) && !/[\u4e00-\u9fff]{4,}/.test(text)) return false;
    return true;
  };

  const addButton = (node, chatReply = false, textOverride = "") => {
    if (!looksLikeReply(node, chatReply, textOverride)) return;
    node.dataset.ttsBrowserHost = "1";
    const palette = paletteFor(node);
    const host = document.createElement("div");
    host.setAttribute(CONTROL_ATTR, "1");
    setStyles(host, {
      display: "flex",
      "justify-content": "flex-start",
      "align-items": "center",
      width: "100%",
      "max-width": "100%",
      "flex-basis": "100%",
      "flex-shrink": "0",
      "align-self": "flex-start",
      "margin": "6px 0 0",
      clear: "both",
    });
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.testid = BUTTON_TEST_ID;
    button.setAttribute("aria-label", "播放这条回复");
    button.textContent = "播放语音";
    if (textOverride) button.__ttsTextOverride = textOverride;
    setStyles(button, {
      all: "unset",
      "box-sizing": "border-box",
      display: "inline-flex",
      "align-items": "center",
      "justify-content": "center",
      width: "auto",
      "min-width": "0",
      "max-width": "100%",
      "min-height": "28px",
      padding: "5px 10px",
      "border-radius": "6px",
      border: `1px solid ${palette.border}`,
      background: palette.bg,
      color: palette.fg,
      "font-family": "inherit",
      "font-size": "13px",
      "font-weight": "500",
      "line-height": "17px",
      cursor: "pointer",
      "white-space": "nowrap",
      "user-select": "none",
    });
    button.addEventListener("mouseenter", () => {
      if (!button.disabled) button.style.setProperty("background", palette.hover, "important");
    });
    button.addEventListener("mouseleave", () => {
      button.style.setProperty("background", palette.bg, "important");
    });
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      speak(node, button);
    });
    host.appendChild(button);
    const tag = node.tagName ? node.tagName.toLowerCase() : "";
    if ((looksLikeVisualTable(node) || tag === "p" || tag === "li" || tag === "blockquote") && node.parentNode) {
      node.insertAdjacentElement("afterend", host);
    } else {
      node.appendChild(host);
    }
  };

  const fitZcodeChatShell = () => {
    if (!/^zcode\.z\.ai$/i.test(location.hostname || "")) return;
    const input = document.querySelector('[data-testid="chat-input"]');
    const chat = document.querySelector('[data-testid="chat-view"]');
    if (!input || !chat) return;
    const composer = input.closest(".w-full.shrink-0") || input.closest("form") || input.closest("div");
    const parent = chat.parentElement;
    if (!composer || !parent || !chat.contains(composer)) return;
    const viewport = window.visualViewport;
    const viewportBottom = viewport ? viewport.offsetTop + viewport.height : window.innerHeight;
    const parentRect = parent.getBoundingClientRect();
    const chatRect = chat.getBoundingClientRect();
    const composerRect = composer.getBoundingClientRect();
    if (parentRect.height < 120 || chatRect.top < parentRect.top - 2) return;
    if (composerRect.bottom <= viewportBottom - 2 && chatRect.bottom <= parentRect.bottom + 2) return;
    setStyles(chat, {
      height: "100%",
      "max-height": "100%",
      overflow: "hidden",
    });
    const scrollRegion = chat.firstElementChild;
    if (scrollRegion && scrollRegion !== composer) {
      setStyles(scrollRegion, {
        "min-height": "0px",
        overflow: "auto",
      });
    }
  };

  const nodeDepth = (node) => {
    let depth = 0;
    for (let item = node; item && item.parentElement; item = item.parentElement) depth += 1;
    return depth;
  };

  const install = () => {
    try {
      installComposerSendTouchFix();
      trimEmbeddedTopGap();
      brandHome();
    } catch {}
    const chatNodes = [];
    CHAT_SELECTORS.forEach((selector) => {
      try {
        document.querySelectorAll(selector).forEach((node) => {
          if (isAssistantReply(node)) chatNodes.push(node);
        });
      } catch {}
    });
    [...new Set(chatNodes)]
      .sort((a, b) => nodeDepth(a) - nodeDepth(b))
      .forEach((node) => {
        const blocks = collectReplyBlocks(node);
        if (blocks.length) {
          groupReplyBlocks(blocks).forEach((group) => addButton(group.node, false, group.text));
        } else {
          addButton(node, true);
        }
      });

    const textNodes = [];
    TEXT_SELECTORS.forEach((selector) => {
      try {
        document.querySelectorAll(selector).forEach((node) => {
          if (!closestBySelectors(node, CHAT_SELECTORS)) textNodes.push(node);
        });
      } catch {}
    });
    [...new Set(textNodes)]
      .sort((a, b) => nodeDepth(b) - nodeDepth(a))
      .forEach((node) => addButton(node, false));

    fitZcodeChatShell();
  };

  new MutationObserver(install).observe(document.documentElement, { childList: true, subtree: true });
  setInterval(install, 1500);
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", install, { once: true });
  } else {
    install();
  }
})();
