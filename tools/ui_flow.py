"""
End-to-end UI walk through every screen of Stylus Notes on a connected device/emulator.

    python tools/ui_flow.py [apk]

Taps controls by their accessibility label (via uiautomator), draws with injected stylus events,
screenshots each step into tools/flow/, and fails on the first app crash.
Multi-finger gestures and palm rejection are covered by the instrumented PalmTest instead
(adb can only inject one pointer).
"""
import html, os, re, subprocess, sys, time

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
PKG = "app.stylusnotes"
OUT = os.path.join(os.path.dirname(__file__), "flow")
os.makedirs(OUT, exist_ok=True)
step = 0
results = []


def adb(*a, out=False):
    r = subprocess.run([ADB, *a], capture_output=True)
    return r.stdout if out else r.returncode


def sh(cmd):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def crashed():
    log = sh("logcat -d -b crash")
    return log if PKG in log else None


def shot(name):
    global step
    step += 1
    path = os.path.join(OUT, f"{step:02d}-{name}.png")
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", out=True))
    c = crashed()
    results.append((name, "CRASH" if c else "ok"))
    print(f"[{step:02d}] {name}: {'CRASH' if c else 'ok'}", flush=True)
    if c:
        print(c)
        sys.exit(1)


def nodes():
    for _ in range(4):
        sh("uiautomator dump /sdcard/ui.xml")
        xml = sh("cat /sdcard/ui.xml")
        if "<hierarchy" in xml:
            return re.findall(r'<node [^>]*>', xml)
        time.sleep(0.6)
    return []


def find(label, exact=True):
    for n in nodes():
        for attr in ("content-desc", "text"):
            m = re.search(attr + r'="([^"]*)"', n)
            v = html.unescape(m.group(1)) if m else ""
            if (v == label) if exact else (label.lower() in v.lower()):
                x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap(label, exact=True, wait=0.8, required=True):
    p = find(label, exact)
    if not p:
        results.append((f"find '{label}'", "MISSING"))
        print(f"   !! could not find '{label}'", flush=True)
        if required:
            shot(f"missing-{re.sub(r'[^a-z0-9]+', '-', label.lower())}")
        return False
    sh(f"input tap {p[0]} {p[1]}")
    time.sleep(wait)
    return True


def tap_xy(x, y, wait=0.6):
    sh(f"input tap {x} {y}"); time.sleep(wait)


def back(wait=0.7):
    sh("input keyevent 4"); time.sleep(wait)


def typ(text):
    sh("input text " + text.replace(" ", "%s").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace("{", "\\{").replace("}", "\\}").replace("^", "\\^"))
    time.sleep(0.4)
    hide_keyboard()


def hide_keyboard():
    if "mInputShown=true" in sh("dumpsys input_method"):
        back(0.5)


def pen(points, src="stylus"):
    """Draws a polyline with the pen."""
    sh(f"input {src} motionevent DOWN {points[0][0]} {points[0][1]}")
    for x, y in points[1:]:
        sh(f"input {src} motionevent MOVE {x} {y}")
    sh(f"input {src} motionevent UP {points[-1][0]} {points[-1][1]}")
    time.sleep(0.3)


def scribble(x, y, w=500, h=120, n=14):
    pen([(x + i * w // n, y + (h if i % 2 else 0)) for i in range(n + 1)])


def main():
    if len(sys.argv) > 1:
        adb("uninstall", PKG)
        assert adb("install", "-r", sys.argv[1]) == 0, "install failed"
    sh("logcat -c -b crash")
    # the system's "Try out your stylus" handwriting onboarding pops over text fields when a pen is used
    sh("settings put secure stylus_handwriting_enabled 0")
    sh("pm clear " + PKG)
    sh(f"am start -n {PKG}/.MainActivity"); time.sleep(3)
    shot("library-empty")

    # ---- create a note ----
    tap("New"); shot("new-sheet")
    tap("New note", wait=2); shot("note-editor")
    scribble(500, 450); scribble(500, 650, n=20)
    shot("note-handwriting")
    tap("Pen"); shot("pen-picker")
    tap("Fountain"); scribble(500, 850, n=10); shot("note-fountain")
    tap("Colour"); shot("colour-panel"); back()
    tap("Eraser"); pen([(560, 450), (700, 700)]); shot("note-erased")
    tap("Lasso"); pen([(420, 380), (1200, 380), (1200, 1000), (420, 1000), (420, 390)]); shot("lasso-selection")
    tap("Duplicate"); shot("duplicated")
    tap("Delete selection"); shot("deleted-selection")
    tap("Undo"); tap("Undo"); tap("Redo"); shot("undo-redo")
    tap("Text"); tap_xy(1500, 500); shot("text-dialog"); typ("Hello from the S Pen"); tap("OK"); shot("text-added")
    tap("Insert"); shot("insert-menu"); tap("Equation"); shot("equation-editor")
    typ("\\frac{a}{b}+\\sqrt{x^2+1}"); time.sleep(0.8); shot("equation-preview"); tap("Done", wait=1.2); shot("equation-added")
    tap("Insert"); tap("Paste"); shot("paste-empty")
    tap("Insert"); tap("Search the web", wait=6); shot("web-image-search"); back(1.5)
    tap("Layers"); shot("note-layers"); tap("Add layer"); shot("note-layer-added"); tap("Layers")
    tap("AI"); shot("ai-needs-key"); tap("Open settings"); shot("settings")
    tap("Anthropic API key (for AI)", exact=False); typ("sk-ant-invalid-test-key"); back(); tap("Done")
    tap("AI"); shot("ai-sheet"); tap("Summarize", wait=12); shot("ai-error-handled"); back()
    tap("More"); shot("more-menu"); tap("Page style"); tap("Grid"); shot("page-grid")
    tap("More"); tap("Paper"); tap("Dark"); shot("paper-dark")
    tap("More"); tap("Share as PDF", wait=2); shot("share-pdf"); back()
    tap("More"); tap("Settings"); tap("Show touch debug overlay"); tap("Done"); scribble(1600, 900, 300, 60, 6); shot("touch-debug")
    tap("Untitled"); typ("Physics notes"); tap("OK"); shot("renamed")
    tap("Back", wait=2); shot("library-with-note")

    # ---- create a drawing ----
    tap("New"); tap("New drawing"); shot("drawing-sizes"); tap("Square", exact=False); tap("Create drawing", wait=2.5); shot("draw-editor")
    scribble(900, 500, 700, 200, 16); shot("draw-stroke")
    tap("Paint"); shot("brush-library"); tap("6B Pencil"); scribble(900, 800, 700, 120, 12); shot("pencil-stroke")
    tap("Smudge"); pen([(900, 520), (1500, 800)]); shot("smudge")
    tap("Erase"); pen([(1000, 450), (1100, 900)]); shot("erase")
    tap("Colour"); shot("draw-colour"); back()
    p = find("Colour")
    if p:
        sh(f"input swipe {p[0]} {p[1]} 1280 1150 900"); time.sleep(2.5); shot("colordrop-fill")
    tap("Layers"); shot("draw-layers"); tap("Add layer"); shot("draw-layer-added")
    tap("Layer 2", exact=False); shot("layer-options"); tap("Multiply"); back(); back()
    tap("Eyedropper"); tap_xy(1280, 1150); shot("eyedropper")
    scribble(900, 1000, 600, 150, 10)  # something on Layer 2 to select and transform
    tap("Selection"); shot("selection-bar"); tap("Rectangle")
    pen([(700, 400), (1700, 1100)]); shot("selection-made"); tap("Copy & paste"); shot("copy-paste")
    tap("Transform", wait=1); shot("transform"); pen([(1200, 700), (1350, 760)]); tap("Rotate 90°"); tap("Done"); shot("transformed")
    tap("Adjustments"); shot("adjust-menu"); tap("Hue, Saturation, Brightness"); shot("hsb-bar"); tap("Apply")
    tap("Adjustments"); tap("Gaussian Blur"); tap("Apply", wait=4); shot("blurred")
    tap("Actions"); shot("actions-menu"); tap("PSD (layered)", wait=6); shot("share-psd"); back()
    tap("Actions"); tap("Flip canvas horizontally", wait=2); shot("flipped")
    tap("Actions"); tap("Preferences"); shot("draw-prefs"); tap("Done")
    tap("Undo"); tap("Redo"); shot("draw-undo-redo")
    tap("Gallery", wait=2); shot("library-both")

    # ---- library actions ----
    tap("Drawings"); shot("filter-drawings"); tap("All")
    p = find("Physics notes")
    if p:
        sh(f"input swipe {p[0]} {p[1]} {p[0]} {p[1]} 900"); time.sleep(1); shot("doc-menu")
        tap("Duplicate"); shot("duplicated-doc")
        sh(f"input swipe {p[0]} {p[1]} {p[0]} {p[1]} 900"); time.sleep(1); tap("Delete"); shot("deleted-with-undo"); tap("Undo"); shot("undo-delete")
    tap("Physics notes", wait=2.5); shot("reopened-note"); tap("Back", wait=2)

    print("\nSUMMARY")
    for name, r in results:
        if r != "ok":
            print(f"  {r}: {name}")
    print(f"  {sum(1 for _, r in results if r == 'ok')} steps ok, {sum(1 for _, r in results if r != 'ok')} problems")


if __name__ == "__main__":
    main()
