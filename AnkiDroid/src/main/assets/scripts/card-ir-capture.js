(() => {
    const styleProperties = [
        "display",
        "position",
        "box-sizing",
        "width",
        "height",
        "min-width",
        "min-height",
        "max-width",
        "max-height",
        "margin-top",
        "margin-right",
        "margin-bottom",
        "margin-left",
        "padding-top",
        "padding-right",
        "padding-bottom",
        "padding-left",
        "border-top-width",
        "border-right-width",
        "border-bottom-width",
        "border-left-width",
        "border-top-style",
        "border-right-style",
        "border-bottom-style",
        "border-left-style",
        "border-top-color",
        "border-right-color",
        "border-bottom-color",
        "border-left-color",
        "border-top-left-radius",
        "border-top-right-radius",
        "border-bottom-right-radius",
        "border-bottom-left-radius",
        "background-color",
        "color",
        "font-family",
        "font-size",
        "font-style",
        "font-weight",
        "line-height",
        "text-align",
        "text-decoration-line",
        "text-decoration-color",
        "text-indent",
        "vertical-align",
        "white-space",
        "word-break",
        "word-spacing",
        "letter-spacing",
        "flex-direction",
        "flex-wrap",
        "flex-grow",
        "flex-shrink",
        "flex-basis",
        "align-items",
        "align-content",
        "align-self",
        "justify-content",
        "justify-items",
        "justify-self",
        "gap",
        "row-gap",
        "column-gap",
        "grid-template-columns",
        "grid-template-rows",
        "grid-auto-flow",
        "overflow",
        "opacity",
        "visibility",
        "direction",
        "object-fit",
        "object-position",
        "overflow-x",
        "overflow-y",
        "fill",
        "fill-opacity",
        "fill-rule",
        "stroke",
        "stroke-opacity",
        "stroke-width",
        "stroke-linecap",
        "stroke-linejoin",
        "list-style-type",
        "list-style-position",
    ];
    const unsupportedTags = new Set([
        "A",
        "AUDIO",
        "BUTTON",
        "CANVAS",
        "EMBED",
        "IFRAME",
        "INPUT",
        "OBJECT",
        "SELECT",
        "TEXTAREA",
        "VIDEO",
    ]);
    const supportedSvgTags = new Set(["svg", "g", "path", "circle", "ellipse", "rect"]);
    const svgAttributes = new Set([
        "viewBox",
        "d",
        "cx",
        "cy",
        "r",
        "rx",
        "ry",
        "x",
        "y",
        "width",
        "height",
    ]);

    function rectOf(rect, origin) {
        return {
            x: rect.left - origin.left,
            y: rect.top - origin.top,
            w: Math.max(0, rect.width),
            h: Math.max(0, rect.height),
        };
    }

    function styleOf(element) {
        const computed = getComputedStyle(element);
        const style = {};
        for (const property of styleProperties) {
            const value = computed.getPropertyValue(property);
            if (value) style[property] = value;
        }
        return style;
    }

    function mediaName(source) {
        try {
            return decodeURIComponent(source).split(/[\\/]/).pop();
        } catch (_) {
            return source.split(/[\\/]/).pop();
        }
    }

    function captureText(textNode, origin, nextId) {
        const text = textNode.nodeValue || "";
        if (!text.trim()) return null;
        const range = document.createRange();
        const runs = [];
        let line = null;
        const lineRect = line =>
            rectOf(
                {
                    left: line.left,
                    top: line.top,
                    width: line.right - line.left,
                    height: line.bottom - line.top,
                },
                origin,
            );
        for (let offset = 0; offset < text.length;) {
            const codePoint = String.fromCodePoint(text.codePointAt(offset));
            range.setStart(textNode, offset);
            range.setEnd(textNode, offset + codePoint.length);
            const rect = range.getBoundingClientRect();
            if (rect.width > 0 && rect.height > 0) {
                if (!line || Math.abs(line.top - rect.top) > 0.5) {
                    if (line) runs.push({ text: line.text, rect: lineRect(line) });
                    line = {
                        text: codePoint,
                        left: rect.left,
                        right: rect.right,
                        top: rect.top,
                        bottom: rect.bottom,
                    };
                } else {
                    line.text += codePoint;
                    line.left = Math.min(line.left, rect.left);
                    line.right = Math.max(line.right, rect.right);
                    line.top = Math.min(line.top, rect.top);
                    line.bottom = Math.max(line.bottom, rect.bottom);
                }
            }
            offset += codePoint.length;
        }
        if (line) runs.push({ text: line.text, rect: lineRect(line) });
        if (!runs.length) return null;
        const bounds = {
            left: Math.min(...runs.map(run => run.rect.x + origin.left)),
            top: Math.min(...runs.map(run => run.rect.y + origin.top)),
            right: Math.max(...runs.map(run => run.rect.x + origin.left + run.rect.w)),
            bottom: Math.max(...runs.map(run => run.rect.y + origin.top + run.rect.h)),
        };
        return {
            id: nextId(),
            type: "text",
            text,
            style: {},
            rect: {
                x: bounds.left - origin.left,
                y: bounds.top - origin.top,
                w: bounds.right - bounds.left,
                h: bounds.bottom - bounds.top,
            },
            runs,
            children: [],
        };
    }

    // A BR between plain text siblings can be represented as separate lines.
    // Inline siblings, leading/trailing breaks, and adjacent breaks need an
    // inline formatting context or empty-line height the Taffy kernel does
    // not model, so those cases stay on WebView.
    function isSimpleBreak(element) {
        const siblings = Array.from(element.parentNode?.childNodes || []).filter(node => {
            if (node.nodeType === Node.COMMENT_NODE) return false;
            if (node.nodeType !== Node.ELEMENT_NODE) return true;
            return !["SCRIPT", "STYLE", "NOSCRIPT"].includes(node.tagName);
        });
        if (
            !siblings.every(
                node =>
                    node.nodeType === Node.TEXT_NODE ||
                    (node.nodeType === Node.ELEMENT_NODE &&
                        (node.tagName === "BR" ||
                            !getComputedStyle(node).display.startsWith("inline"))),
            )
        )
            return false;
        const meaningful = siblings.filter(
            node =>
                (node.nodeType === Node.TEXT_NODE && !!node.nodeValue?.trim()) ||
                (node.nodeType === Node.ELEMENT_NODE && node.tagName === "BR"),
        );
        const index = meaningful.indexOf(element);
        return (
            index > 0 &&
            index < meaningful.length - 1 &&
            meaningful[index - 1].nodeType === Node.TEXT_NODE &&
            meaningful[index + 1].nodeType === Node.TEXT_NODE
        );
    }

    function listMarkerInfo(element) {
        if (element.tagName !== "LI") return { supported: true, marker: null };
        const style = getComputedStyle(element);
        const markerContent = getComputedStyle(element, "::marker").content;
        if (markerContent !== "normal" && markerContent !== "none") return { supported: false };
        if (style.listStylePosition !== "outside") return { supported: false };
        const type = style.listStyleType;
        if (type === "none") return { supported: true, marker: null };
        if (["disc", "circle", "square"].includes(type)) {
            return { supported: true, marker: { disc: "•", circle: "◦", square: "▪" }[type] };
        }
        if (type !== "decimal") return { supported: false };
        const list = element.parentElement;
        if (list?.tagName !== "OL" || list.hasAttribute("reversed")) return { supported: false };
        const items = Array.from(list.children).filter(child => child.tagName === "LI");
        let ordinal = Number.parseInt(list.getAttribute("start") || "1", 10);
        for (const item of items) {
            const value = Number.parseInt(item.getAttribute("value") || "", 10);
            if (Number.isFinite(value)) ordinal = value;
            if (item === element) break;
            ordinal++;
        }
        return { supported: true, marker: `${ordinal}.` };
    }

    function captureElement(element, origin, nextId, unsupported, inSvg = false) {
        const tag = element.tagName;
        if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT") return null;
        const computed = getComputedStyle(element);
        const audioLink =
            tag === "A" &&
            element.classList.contains("replay-button") &&
            element.classList.contains("soundLink") &&
            /^playsound:[qa]:\d+$/.test(element.getAttribute("href") || "");
        const linkHref = element.getAttribute("href") || "";
        const externalLink = tag === "A" && /^(?:https?:|mailto:|tel:)/i.test(linkHref);
        const clozeSpan = tag === "SPAN" && /\bcloze\b/i.test(element.className || "");
        const svgNode = inSvg || tag === "SVG";
        const capturedInline = tag === "SUP" || tag === "SUB" || clozeSpan;
        const simpleBreak = tag === "BR" && isSimpleBreak(element);
        const listMarker = listMarkerInfo(element);
        if (!listMarker.supported) unsupported.add("list-marker");
        if (
            computed.transform !== "none" ||
            computed.position === "fixed" ||
            computed.position === "sticky"
        ) {
            unsupported.add(`css-layout:${tag.toLowerCase()}`);
        }
        if (computed.display === "contents" || computed.visibility === "collapse") {
            unsupported.add(`css-layout:${tag.toLowerCase()}`);
        }
        // Taffy has no CSS inline formatting context. The current Android IR
        // tree would turn multiple inline peers into separate block children;
        // a lone inline child is equivalent to a block in this parent flow.
        const hasInlinePeers = Array.from(element.parentNode?.childNodes || []).some(sibling => {
            if (sibling === element) return false;
            if (sibling.nodeType === Node.TEXT_NODE) return !!sibling.nodeValue?.trim();
            if (sibling.nodeType !== Node.ELEMENT_NODE) return false;
            return getComputedStyle(sibling).display.startsWith("inline");
        });
        if (
            computed.display.startsWith("inline") &&
            hasInlinePeers &&
            !simpleBreak &&
            !capturedInline
        ) {
            unsupported.add(`inline-layout:${tag.toLowerCase()}`);
        }
        if (
            computed.backgroundImage !== "none" ||
            computed.boxShadow !== "none" ||
            computed.textShadow !== "none" ||
            computed.filter !== "none" ||
            computed.animationName !== "none"
        ) {
            unsupported.add(`css-paint:${tag.toLowerCase()}`);
        }
        const borderRadiusProperties = [
            "border-top-left-radius",
            "border-top-right-radius",
            "border-bottom-right-radius",
            "border-bottom-left-radius",
        ];
        if (
            borderRadiusProperties.some(
                property => computed.getPropertyValue(property).trim().split(/\s+/).length > 1,
            )
        ) {
            unsupported.add(`css-border-radius:${tag.toLowerCase()}`);
        }
        for (const side of ["top", "right", "bottom", "left"]) {
            const width = Number.parseFloat(computed.getPropertyValue(`border-${side}-width`)) || 0;
            const borderStyle = computed.getPropertyValue(`border-${side}-style`);
            if (width > 0 && !["solid", "dashed", "dotted", "double"].includes(borderStyle)) {
                unsupported.add(`css-border-style:${tag.toLowerCase()}`);
                break;
            }
        }
        const decorationLines = computed.textDecorationLine
            .split(/\s+/)
            .filter(line => line && line !== "none");
        const supportedDecorationLines = new Set(["underline", "line-through"]);
        if (
            decorationLines.some(line => !supportedDecorationLines.has(line)) ||
            (decorationLines.length > 0 && computed.textDecorationStyle !== "solid") ||
            (decorationLines.length > 0 && computed.textDecorationColor !== computed.color) ||
            (decorationLines.length > 0 && element.children.length > 0)
        ) {
            unsupported.add(`css-text-decoration:${tag.toLowerCase()}`);
        }
        if (
            computed.textTransform !== "none" ||
            computed.writingMode !== "horizontal-tb" ||
            computed.direction === "rtl"
        ) {
            unsupported.add(`css-text:${tag.toLowerCase()}`);
        }
        if (computed.textAlign === "justify" && /[\u2e80-\u9fff]/.test(element.textContent || "")) {
            unsupported.add(`css-justify-cjk:${tag.toLowerCase()}`);
        }
        // Chromium's default <hr> styling uses overflow:hidden to clip its
        // own border/background. HR is a leaf in the captured tree, so this
        // does not clip descendant content; the resolved one-pixel box is
        // painted directly by the native renderer. Keep clipping fallbacks
        // for every element that can contain content.
        const clipsOverflow = [computed.overflowX, computed.overflowY].every(
            value => value === "hidden" || value === "clip",
        );
        if (
            tag !== "HR" &&
            tag !== "SVG" &&
            (computed.overflowX !== "visible" || computed.overflowY !== "visible") &&
            !clipsOverflow
        ) {
            unsupported.add(`css-clipping:${tag.toLowerCase()}`);
        }
        if (
            ["::before", "::after"].some(pseudo => {
                const content = getComputedStyle(element, pseudo).content;
                return content !== "none" && content !== "normal" && content !== "";
            })
        ) {
            unsupported.add(`pseudo-content:${tag.toLowerCase()}`);
        }
        if (
            (unsupportedTags.has(tag) && !audioLink && !externalLink) ||
            Array.from(element.attributes).some(attribute =>
                attribute.name.toLowerCase().startsWith("on"),
            ) ||
            (svgNode &&
                (!supportedSvgTags.has(tag.toLowerCase()) ||
                    (inSvg && tag === "SVG") ||
                    element.hasAttribute("transform") ||
                    computed.clipPath !== "none" ||
                    (tag === "RECT" &&
                        ["rx", "ry"].some(
                            name => Number.parseFloat(element.getAttribute(name) || "0") !== 0,
                        )) ||
                    computed.fillOpacity !== "1" ||
                    computed.strokeOpacity !== "1" ||
                    computed.strokeDasharray !== "none")) ||
            (tag === "IMG" &&
                /\.svg(?:$|[?#])/i.test(element.getAttribute("src") || element.currentSrc || "")) ||
            (tag === "IMG" &&
                /^(?:data|blob|https?):/i.test(
                    element.getAttribute("src") || element.currentSrc || "",
                )) ||
            (tag === "IMG" &&
                computed.objectFit !== "fill" &&
                !(computed.objectFit === "contain" && computed.objectPosition === "50% 50%"))
        ) {
            unsupported.add(`interactive:${tag.toLowerCase()}`);
        }
        if (tag === "BR" && !simpleBreak) unsupported.add("line-break");
        const bounds = element.getBoundingClientRect();
        if (bounds.width <= 0 && bounds.height <= 0 && tag !== "BR") return null;
        const type = audioLink
            ? "audio"
            : svgNode
              ? "svg"
              : tag === "IMG"
                ? "image"
                : tag === "BR"
                  ? "break"
                  : tag === "RUBY"
                    ? "ruby"
                    : clozeSpan
                      ? "cloze"
                      : "box";
        const attrs = {};
        if (clozeSpan) {
            attrs.class = element.className;
            const ordinal = element.getAttribute("data-ordinal");
            if (ordinal != null) attrs["data-ordinal"] = ordinal;
            const answer = element.getAttribute("data-cloze");
            if (answer != null) attrs["data-cloze"] = answer;
            else unsupported.add("cloze-answer-unavailable");
        }
        if (svgNode) {
            for (const attribute of element.attributes) {
                if (svgAttributes.has(attribute.name)) attrs[attribute.name] = attribute.value;
            }
        }
        if (capturedInline) attrs["captured-inline"] = "true";
        const node = {
            id: nextId(),
            type,
            tag: tag.toLowerCase(),
            attrs,
            style: styleOf(element),
            rect: rectOf(bounds, origin),
            runs: [],
            actions:
                clozeSpan &&
                attrs["data-cloze"] != null &&
                element.textContent !== attrs["data-cloze"]
                    ? [{ on: "reveal", kind: "reveal-cloze" }]
                    : [],
            children: [],
        };
        if (listMarker.marker) node.attrs["list-marker"] = listMarker.marker;
        if (tag === "IMG")
            node.src = mediaName(element.getAttribute("src") || element.currentSrc || "");
        if (audioLink) node.src = element.getAttribute("href");
        if (externalLink) node.src = linkHref;

        if (!audioLink) {
            for (const child of element.childNodes) {
                const captured =
                    child.nodeType === Node.TEXT_NODE
                        ? captureText(child, origin, nextId)
                        : child.nodeType === Node.ELEMENT_NODE
                          ? captureElement(child, origin, nextId, unsupported, svgNode)
                          : null;
                if (captured) node.children.push(captured);
            }
        }
        return node;
    }

    window.ankiCaptureCardIr = function (side, cardId, isDark) {
        const root =
            document.querySelector("body.card") || document.querySelector("#qa") || document.body;
        if (!root || !root.getBoundingClientRect().width) return null;
        let id = 1;
        const nextId = () => id++;
        const unsupported = new Set();
        if (parseFloat(getComputedStyle(document.body).zoom || "1") !== 1)
            unsupported.add("webview-zoom");
        const rootRect = root.getBoundingClientRect();
        const rootNode = {
            id: 0,
            type: "box",
            tag: root.tagName.toLowerCase(),
            style: styleOf(root),
            rect: {
                x: 0,
                y: 0,
                w: root.clientWidth || rootRect.width,
                h: Math.max(root.scrollHeight, root.clientHeight),
            },
            runs: [],
            children: [],
        };
        for (const child of root.childNodes) {
            const captured =
                child.nodeType === Node.TEXT_NODE
                    ? captureText(child, rootRect, nextId)
                    : child.nodeType === Node.ELEMENT_NODE
                      ? captureElement(child, rootRect, nextId, unsupported)
                      : null;
            if (captured) rootNode.children.push(captured);
        }
        const width = Math.max(1, Math.round(rootNode.rect.w));
        const variant = `${isDark ? "dark" : "light"}@${width}x1`;
        const nodeCount = (() => {
            let count = 0;
            const visit = node => {
                count++;
                node.children.forEach(visit);
            };
            visit(rootNode);
            return count;
        })();
        const textRunCount = (() => {
            let count = 0;
            const visit = node => {
                count += node.runs.length;
                node.children.forEach(visit);
            };
            visit(rootNode);
            return count;
        })();
        return {
            side,
            variant,
            frame: {
                width,
                height: rootNode.rect.h,
                root: rootNode,
                node_count: nodeCount,
                text_run_count: textRunCount,
            },
            unsupported: Array.from(unsupported),
            card_id: String(cardId),
        };
    };
})();
