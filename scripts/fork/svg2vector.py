#!/usr/bin/env python3
"""Convert lobe-icons SVGs to Android VectorDrawable XML.

These icons are a narrow, uniform subset: viewBox="0 0 24 24", a handful of
shapes, optional per-shape fill, optional fill-rule. That is close to what
VectorDrawable expresses natively, so a targeted converter is both sufficient
and auditable — the Android SDK's vd-tool is not available in this sandbox.

Three deliberate simplifications, all invisible at the 20dp these render at:

  * **Gradients are flattened to one solid ARGB** — the area-weighted average
    over the whole ramp, premultiplied so transparent stops contribute no hue.
    VectorDrawable can express gradients (via aapt inline resources) but at icon
    size a two-stop brand gradient is indistinguishable from its average, and
    flattening keeps the generated XML reviewable. Averaging rather than sampling
    one point matters: icons like Gemini and Meta stack overlays that fade to
    `stop-opacity="0"`, and a single sample either lands in the opaque end (every
    overlay becomes a solid repaint of the same silhouette — the Gemini star came
    out solid yellow) or in the dead zone (the overlay disappears).
  * **circle / ellipse / rect are rewritten as path data** using two elliptical
    arcs (a full-circle arc is degenerate and some renderers drop it).
  * **currentColor / unset fill** becomes the caller-supplied mono colour, which
    the UI then tints at runtime.

Anything still outside the subset (masks, <use>, <text>…) is REPORTED AND
SKIPPED rather than silently mangled: a half-converted icon rendering as a black
square is worse than a missing one, because the fallback path (the model's
initial letter) already handles absence gracefully.
"""
import re
import sys
import os
import xml.etree.ElementTree as ET

SVG_NS = "http://www.w3.org/2000/svg"

TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from lobe-icons (MIT) by scripts/fork/svg2vector.py — do not hand-edit. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
{paths}
</vector>
'''

PATH_TEMPLATE = '''    <path
        android:fillColor="{color}"{extra}
        android:pathData="{data}" />'''


def norm_color(value):
    """SVG fill → Android colour, or None for currentColor/unset, "none" to skip."""
    if not value or value in ("currentColor", "inherit"):
        return None
    v = value.strip()
    if v == "none":
        return "none"
    if v.startswith("#"):
        h = v[1:]
        if len(h) == 3:
            h = "".join(c * 2 for c in h)
        if len(h) == 6:
            return "#FF" + h.upper()
        if len(h) == 8:
            return "#" + (h[6:] + h[:6]).upper()
    m = re.match(r'rgba?\(([^)]+)\)', v)
    if m:
        parts = [p.strip() for p in m.group(1).split(",")]
        r, g, b = (int(float(p)) for p in parts[:3])
        a = int(float(parts[3]) * 255) if len(parts) > 3 else 255
        return "#%02X%02X%02X%02X" % (a, r, g, b)
    named = {
        "black": "#FF000000", "white": "#FFFFFFFF", "red": "#FFFF0000",
        "blue": "#FF0000FF", "green": "#FF008000", "gray": "#FF808080",
        "grey": "#FF808080",
    }
    return named.get(v.lower(), None)


def parse_stops(node):
    """[(offset, '#AARRGGBB')] for one gradient, ordered by offset."""
    stops = []
    for stop in node:
        if stop.tag.split("}")[-1] != "stop":
            continue
        off = stop.get("offset", "0")
        off = float(off[:-1]) / 100 if off.endswith("%") else float(off)
        color = stop.get("stop-color")
        opacity = stop.get("stop-opacity")
        style = stop.get("style", "")
        if color is None:
            m = re.search(r'stop-color:\s*([^;]+)', style)
            color = m.group(1) if m else None
        if opacity is None:
            m = re.search(r'stop-opacity:\s*([^;]+)', style)
            opacity = m.group(1) if m else None
        resolved = norm_color(color)
        if not resolved or resolved == "none":
            continue
        a = int(round(float(opacity) * 255)) if opacity is not None else 255
        stops.append((off, "#%02X%s" % (max(0, min(255, a)), resolved[3:])))
    stops.sort(key=lambda s: s[0])
    return stops


def lerp_argb(c1, c2, t):
    a = [int(c1[i:i + 2], 16) for i in (1, 3, 5, 7)]
    b = [int(c2[i:i + 2], 16) for i in (1, 3, 5, 7)]
    return "#" + "".join(
        "%02X" % int(round(x + (y - x) * t)) for x, y in zip(a, b)
    )


def average_argb(stops):
    """Area-weighted average colour of a piecewise-linear gradient over [0,1].

    Averaging rather than sampling one point, because sampling has a degenerate
    case that actually occurs in this icon set: the Gemini yellow overlay fades
    from opaque at offset 0 to `stop-opacity="0"` at offset 0.46 and stays
    transparent for the remaining 54%. A midpoint sample lands in that dead zone
    and yields alpha 0 — the overlay vanishes entirely. The average (~23% alpha)
    is both non-degenerate and closer to what the eye integrates at 20dp.

    Colour channels are averaged PREMULTIPLIED by alpha and then un-premultiplied,
    so a transparent stop contributes no hue. Averaging straight RGB would let
    the invisible end of a fade drag the visible colour toward it.
    """
    if not stops:
        return None
    if len(stops) == 1:
        return stops[0][1]

    def chans(c):
        return [int(c[i:i + 2], 16) for i in (1, 3, 5, 7)]  # a, r, g, b

    acc = [0.0, 0.0, 0.0, 0.0]  # ∫alpha, ∫alpha*r, ∫alpha*g, ∫alpha*b
    total = 0.0

    def add_flat(color, width):
        nonlocal total
        if width <= 0:
            return
        a, r, g, b = chans(color)
        af = a / 255.0
        acc[0] += af * width
        acc[1] += af * r * width
        acc[2] += af * g * width
        acc[3] += af * b * width
        total += width

    # Constant extension before the first stop and after the last — that is how
    # SVG pads a gradient (spreadMethod="pad", the default).
    add_flat(stops[0][1], stops[0][0])
    for i in range(len(stops) - 1):
        (o1, c1), (o2, c2) = stops[i], stops[i + 1]
        width = o2 - o1
        if width <= 0:
            continue
        # Trapezoid rule is exact for a linear ramp.
        add_flat(lerp_argb(c1, c2, 0.5), width)
    add_flat(stops[-1][1], 1.0 - stops[-1][0])

    if total <= 0:
        return stops[0][1]
    a_avg = acc[0] / total
    if a_avg <= 0.002:
        # Effectively invisible; keep the hue of the most opaque stop at a floor
        # alpha so the shape still reads rather than disappearing.
        best = max(stops, key=lambda s: int(s[1][1:3], 16))
        return "#20" + best[1][3:]
    r = acc[1] / acc[0]
    g = acc[2] / acc[0]
    b = acc[3] / acc[0]
    return "#%02X%02X%02X%02X" % (
        int(round(max(0, min(255, a_avg * 255)))),
        int(round(max(0, min(255, r)))),
        int(round(max(0, min(255, g)))),
        int(round(max(0, min(255, b)))),
    )


def collect_gradients(root):
    """id → the gradient flattened to one solid ARGB (see [average_argb])."""
    out = {}
    for node in root.iter():
        tag = node.tag.split("}")[-1]
        if tag not in ("linearGradient", "radialGradient"):
            continue
        gid = node.get("id")
        if not gid:
            continue
        flat = average_argb(parse_stops(node))
        if flat:
            out[gid] = flat
    return out


def ellipse_path(cx, cy, rx, ry):
    """Full ellipse as two arcs — a single 360° arc is degenerate in SVG/VD."""
    return (
        f"M{cx - rx},{cy}"
        f"a{rx},{ry} 0 1,0 {rx * 2},0"
        f"a{rx},{ry} 0 1,0 {-rx * 2},0z"
    )


def rect_path(x, y, w, h):
    return f"M{x},{y}h{w}v{h}h{-w}z"


def f(node, name, default=0.0):
    try:
        return float(node.get(name, default))
    except (TypeError, ValueError):
        return default


def convert(svg_text, mono_color="#FF000000"):
    """Return (xml, warnings). xml is None when the icon is unsupported."""
    warnings = []
    svg_text = re.sub(r'<\?xml[^>]*\?>', '', svg_text)
    svg_text = re.sub(r'<!DOCTYPE[^>]*>', '', svg_text)
    try:
        root = ET.fromstring(svg_text)
    except ET.ParseError as e:
        return None, [f"parse error: {e}"]

    vb = root.get("viewBox", "0 0 24 24").split()
    if len(vb) == 4 and (vb[2] != "24" or vb[3] != "24"):
        warnings.append(f"viewBox {' '.join(vb)} (expected 24x24)")

    gradients = collect_gradients(root)
    unsupported = set()
    paths = []

    def resolve_fill(value):
        """Returns an Android colour, or None to use the mono colour, or "none"."""
        if value and value.startswith("url("):
            gid = value[4:-1].strip().lstrip("#")
            flat = gradients.get(gid)
            if flat:
                return flat
            warnings.append(f"unresolved gradient {gid} → mono")
            return None
        return norm_color(value)

    def emit(data, fill, rule):
        color = resolve_fill(fill)
        if color == "none":
            return
        if color is None:
            color = mono_color
        extra = ""
        if rule in ("evenodd", "nonzero"):
            extra = f'\n        android:fillType="{rule}"'
        paths.append(PATH_TEMPLATE.format(
            color=color, extra=extra, data=data.replace('"', "'").strip(),
        ))

    def walk(node, inherited_fill, inherited_rule):
        for child in node:
            tag = child.tag.split("}")[-1]
            fill = child.get("fill", inherited_fill)
            rule = child.get("fill-rule", inherited_rule)
            if tag == "g":
                walk(child, fill, rule)
            elif tag in ("title", "desc", "defs", "linearGradient",
                         "radialGradient", "stop", "style"):
                continue
            elif tag == "path":
                d = child.get("d")
                if d:
                    emit(d, fill, rule)
            elif tag == "circle":
                r = f(child, "r")
                if r > 0:
                    emit(ellipse_path(f(child, "cx"), f(child, "cy"), r, r), fill, rule)
            elif tag == "ellipse":
                rx, ry = f(child, "rx"), f(child, "ry")
                if rx > 0 and ry > 0:
                    emit(ellipse_path(f(child, "cx"), f(child, "cy"), rx, ry), fill, rule)
            elif tag == "rect":
                w, h = f(child, "width"), f(child, "height")
                if child.get("rx") or child.get("ry"):
                    # Rounded rects would need arc corners; none of the icons in
                    # this set use them, so flag rather than approximate.
                    unsupported.add("rect[rounded]")
                elif w > 0 and h > 0:
                    emit(rect_path(f(child, "x"), f(child, "y"), w, h), fill, rule)
            else:
                unsupported.add(tag)

    walk(root, root.get("fill"), root.get("fill-rule"))

    if unsupported:
        return None, warnings + [f"unsupported elements: {sorted(unsupported)}"]
    if not paths:
        return None, warnings + ["no drawable paths"]
    return TEMPLATE.format(paths="\n".join(paths)), warnings


def main():
    if len(sys.argv) < 3:
        print("usage: svg2vector.py <src.svg> <dst.xml> [mono_color]", file=sys.stderr)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    mono = sys.argv[3] if len(sys.argv) > 3 else "#FF000000"
    xml, warnings = convert(open(src, encoding="utf-8").read(), mono)
    for w in warnings:
        print(f"  warn {os.path.basename(src)}: {w}", file=sys.stderr)
    if xml is None:
        print(f"  SKIP {os.path.basename(src)}", file=sys.stderr)
        return 1
    with open(dst, "w", encoding="utf-8") as f_out:
        f_out.write(xml)
    return 0


if __name__ == "__main__":
    sys.exit(main())
