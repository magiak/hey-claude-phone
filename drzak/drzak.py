"""Držák domácího asistentu: tělo s okénkem pro telefon na šířku a podstavec na stůl.

Tělo = rámeček (tiskne se čelem dolů) + víčko (tiskne se zády dolů), spojené 4 šroubky
M2,5×10 se zápustnou hlavou jako zadní kryt Live Display. Konektor drží koncová stěna těla.
Na stůl se tělo zasune do podstavce. Na zeď se stejné tělo pověsí víčkem na dva šrouby.

Spuštění: python drzak.py → stl/*.stl, asistent_drzak.3mf, nahled.png
Souřadnice těla: x vodorovně (střed okénka = 0), y nahoru, z od čela (0) dozadu.
"""
import sys
from pathlib import Path

import numpy as np
import trimesh
from manifold3d import CrossSection, JoinType, Manifold

# The Bambu Studio 3MF writer is a local helper outside this repo; the STL files work without it.
sys.path.insert(0, str(Path.home() / ".claude/tools/printcheck"))
try:
    from bambu3mf import write_3mf
except ImportError:
    write_3mf = None

OUT = Path(__file__).parent
sys.stdout.reconfigure(encoding="utf-8")

# ---------- TELEFON (ASUS ZenFone 5Z, na šířku, výřez kamery vlevo, tlačítka nahoře) ----------
PH_L, PH_W, PH_T = 153.0, 75.65, 7.9    # výrobce, NEZMĚŘENO; tloušťka 7,7 nebo 7,9 podle zdroje, bere se větší
DISP_L, DISP_W, NOTCH_D = 141.0, 68.0, 5.5   # z adb: 2246 × 1080 px při 404,6 dpi
DISP_X0 = 4.0          # okraj nad displejem u výřezu, odhad. Černé pozadí nástěnky chybu ±2 mm schová
SLACK_L, SLACK_W = 0.8, 0.6   # vůle kapsy celkem
BACK_GAP = 1.5         # za telefonem: hrbol kamery se nesmí opřít o víčko
PRESS_CLR = 0.3        # žebra víčka tlačí telefon k čelu s touhle vůlí
# Za pravým koncem úhlový konektor USB-C. Stěna ho drží zasunutý: v prvním tisku (16 mm)
# za ním zbyly ~4 mm a kabel vypadával (řekl 2026-11-08); o 3,5 mm míň nechá 0,5 mm vůle na vložení.
PLUG_SPACE = 12.5
GAUGE_T = 16.0 - PLUG_SPACE    # destička do mezery v prvním tisku: když vleze, nová stěna konektor nezmáčkne

# ---------- TĚLO ----------
FRONT_T, WALL, COVER_T = 2.0, 2.0, 1.8
HH = 48.0              # polovina výšky těla: rámeček nad a pod okénkem ~15 mm, nevypadá to jako telefon
R_OUT = 10.0
CH_FRONT, CH_BACK = 1.5, 0.6
WIN_MARGIN, WIN_R, WIN_CH = 1.0, 4.0, 1.6   # okénko 1 mm uvnitř displeje, sražení 39° k čelu
RIB_T = 1.6
STOP_LEN = 12.0        # zarážky nahoře jen u konců: tlačítka jsou nahoře uprostřed
GRILLE_D, GRILLE_PITCH = 2.0, 4.0
BOSS_D = 6.5
COV_CLR = 0.25
# Šroubky jako zadní kryt Live Display: v Ø2,5 si M2,5 vyřízne závit v PLA (zašroubováno 2026-10-17).
SCREW_LEN, SCREW_HOLE_D = 10.0, 2.5
SCREW_FREE_D = 2.8     # ve víčku volně: se závitem v obou dílech by šroub víčko od sloupku odtlačil
HEAD_D = 5.584         # hlava 90°
HEAD_SINK = 0.3        # hlava pod povrchem, první vrstva na podložce díru trochu zúží
RING_H = 1.8           # víčko má jen 1,8 mm a kužel hlavy 1,4 mm, prstenec pod ním dosedá na sloupek
CABLE_X, CABLE_W, CABLE_Z0 = 45.0, 7.0, 3.0   # kabel vede spodní lištou a vychází dole uvnitř délky podstavce
# Hlava šroubu ve zdi vjíždí až k žebru u konce telefonu, výš by o něj zavadila.
KEY_Y, KEY_HEAD, KEY_SLOT, KEY_RISE, KEY_PAD = 10.0, 8.5, 4.2, 9.0, 1.2

# ---------- PODSTAVEC: pás vpředu přes celou šířku, nožky pod konci těla (plný kvádr měl 75 g, řekl 2026-11-08 „hrozně moc") ----------
# Pás je zepředu všude stejně vysoký: konce nožek trčící nad nízkou lištou vypadaly jako packy (řekl 2026-11-09).
TILT = 15.0            # sklon dozadu
CH_BASE, CH_TOP = 0.8, 0.8
FOOT_D = 55.0          # dozadu tak daleko, aby ho klepnutí nahoře na displej nepřevrátilo
FOOT_IN = 15.0         # pod tělem 5 mm rovného dna před zaobleným rohem
END_T, END_CLR = 2.0, 0.6   # vůli drážky ověřil první stojánek, délku těla ne: u konců víc
BAND_T, TAIL_H = 2.0, 3.0
BODY_Y0, BODY_Z0 = 2.5, 14.0   # přední spodní hrana těla; pod tělem se kabel stočí dozadu mezi nožkami
SLOT_DEPTH, SLOT_CLR, LIP = 8.0, 0.3, 2.5

# ---------- ODVOZENÉ ----------
WW = DISP_L - NOTCH_D - 2 * WIN_MARGIN
WH = DISP_W - 2 * WIN_MARGIN
PX0 = -(DISP_X0 + NOTCH_D + WIN_MARGIN + WW / 2)    # levý konec telefonu
CX0, CX1 = PX0 - SLACK_L / 2, PX0 + PH_L + SLACK_L / 2
CY1 = (PH_W + SLACK_W) / 2
CY0 = -CY1
HL_IN = CX1 + PLUG_SPACE        # souměrně: vlevo stejně volného místa jako vpravo pro konektor
HL = HL_IN + WALL
HH_IN = HH - WALL
D = FRONT_T + PH_T + BACK_GAP + COVER_T
Z_COV = D - COVER_T
BOSS_Y = HH_IN - COV_CLR - 0.5 - (HEAD_D + 0.2) / 2   # hlava šroubu musí zůstat celá ve víčku
BOSSES = [(-62.0, BOSS_Y), (62.0, BOSS_Y), (-62.0, -BOSS_Y), (20.0, -BOSS_Y)]   # dole vpravo vede kabel
BOSS_TOP = Z_COV - RING_H
HOLE_BOTTOM = D - HEAD_SINK - SCREW_LEN - 0.5
assert HOLE_BOTTOM >= FRONT_T + 0.3, "šroub by prorazil čelo"
assert BOSS_Y - BOSS_D / 2 >= CY1 + 0.9, "sloupek zasahuje do telefonu"
BASE_HL = HL + END_CLR + END_T
GRILLE_X = (CX1 + HL_IN) / 2
KEY_X = (CX1 + RIB_T + HL_IN) / 2


def rrect(w, h, r, cx=0.0, cy=0.0):
    r = max(min(r, w / 2 - 0.01, h / 2 - 0.01), 0.01)
    return CrossSection.square([w - 2 * r, h - 2 * r], center=True).offset(r, JoinType.Round, 2.0, 64).translate([cx, cy])


def ext(cs, z0, z1):
    return Manifold.extrude(cs, z1 - z0).translate([0, 0, z0])


def box(x0, x1, y0, y1, z0, z1):
    return Manifold.cube([x1 - x0, y1 - y0, z1 - z0]).translate([x0, y0, z0])


def cyl(x, y, z0, z1, d, d_top=None):
    return Manifold.cylinder(z1 - z0, d / 2, (d_top or d) / 2, 48).translate([x, y, z0])


def chamfered(cs, z0, z1, ch0, ch1):
    """Konvexní obrys se sražením dole a nahoře (45°)."""
    parts = [ext(cs, z0 + ch0, z1 - ch1)]
    if ch0:
        parts.append(ext(cs.offset(-ch0, JoinType.Round, 2.0, 64), z0, z0 + 0.01))
    if ch1:
        parts.append(ext(cs.offset(-ch1, JoinType.Round, 2.0, 64), z1 - 0.01, z1))
    return Manifold.batch_hull(parts)


def one(m, name):
    assert len(m.decompose()) == 1, f"{name}: víc kusů ({len(m.decompose())})"
    return m


def build_shell():
    m = chamfered(rrect(2 * HL, 2 * HH, R_OUT), 0, D, CH_FRONT, CH_BACK)
    m -= ext(rrect(2 * HL_IN, 2 * HH_IN, R_OUT - WALL), FRONT_T, D + 1)
    m -= Manifold.batch_hull([ext(rrect(WW + 2 * WIN_CH, WH + 2 * WIN_CH, WIN_R + WIN_CH), -1, 0),
                              ext(rrect(WW, WH, WIN_R), FRONT_T, FRONT_T + 1)])
    # mřížka po stranách: vpravo z ní jde zvuk spodního reproduktoru a mikrofonu, vlevo sluchátka
    for sx in (-1, 1):
        for i in (-1, 0, 1):
            for j in range(-3, 4):
                m -= cyl(sx * GRILLE_X + i * GRILLE_PITCH, j * GRILLE_PITCH, -1, FRONT_T + 1, GRILLE_D)

    z0, z1 = FRONT_T - 0.3, Z_COV - KEY_PAD - 0.2    # pod podložkami klíčových dírek, telefon končí na 9,9
    feats = [
        box(CX0, CX1, CY0 - RIB_T, CY0, z0, z1),                              # dole celá délka
        box(CX0 - RIB_T, CX0, -HH_IN - 0.5, CY0 + 15, z0, z1),                 # vlevo dole
        box(CX0 - RIB_T, CX0, CY1 - 15, HH_IN + 0.5, z0, z1),                  # vlevo nahoře
        box(CX0 - RIB_T, CX0 + STOP_LEN, CY1, HH_IN + 0.5, z0, z1),            # nahoře vlevo
        box(CX1 - STOP_LEN, CX1 + RIB_T, CY1, HH_IN + 0.5, z0, z1),            # nahoře vpravo
        box(CX1, CX1 + RIB_T, CY1 - 15, HH_IN + 0.5, z0, z1),                  # vpravo nahoře; dole vede kabel
    ]
    feats += [cyl(x, y, z0, BOSS_TOP, BOSS_D) for x, y in BOSSES]
    m += sum(feats[1:], feats[0])
    room = ext(rrect(2 * HL_IN, 2 * HH_IN, R_OUT - WALL), BOSS_TOP, D + 1)
    for x, y in BOSSES:
        m -= cyl(x, y, BOSS_TOP, D + 1, BOSS_D + 0.6) ^ room    # místo pro prstenec víčka
        m -= cyl(x, y, HOLE_BOTTOM, BOSS_TOP + 1, SCREW_HOLE_D)
    m -= box(CABLE_X - CABLE_W / 2, CABLE_X + CABLE_W / 2, -HH - 1, -HH_IN + 0.01, CABLE_Z0, D + 1)
    m -= box(-6, 6, HH_IN - 0.5, HH + 1, D - 1.5, D + 1)        # zářez na nehet, víčko jde vypáčit
    return one(m, "tělo")


def build_cover_body():
    """Víčko v souřadnicích těla."""
    outline = rrect(2 * (HL_IN - COV_CLR), 2 * (HH_IN - COV_CLR), R_OUT - WALL - COV_CLR)
    m = ext(outline, Z_COV, D)
    m += box(CABLE_X - CABLE_W / 2 + 0.2, CABLE_X + CABLE_W / 2 - 0.2, -HH + CH_BACK, -HH_IN + 0.5, Z_COV, D)
    zf = Z_COV + 0.3
    for x, y in BOSSES:
        m += cyl(x, y, BOSS_TOP, zf, BOSS_D) ^ ext(outline, BOSS_TOP, zf)
    # tlačí telefon k čelu podél dlouhých hran; nahoře až za kamerou
    yr = PH_W / 2 - 3
    press = Z_COV - (BACK_GAP - PRESS_CLR)
    m += box(-70, 65, -yr - 0.75, -yr + 0.75, press, zf)
    m += box(-40, 65, yr - 0.75, yr + 0.75, press, zf)
    for sx in (-1, 1):
        m += ext(rrect(11, 24, 4, sx * KEY_X, KEY_Y + 4) ^ outline, Z_COV - KEY_PAD, zf)
    for sx in (-1, 1):
        kx = sx * KEY_X
        m -= cyl(kx, KEY_Y, Z_COV - KEY_PAD - 1, D + 1, KEY_HEAD)
        m -= box(kx - KEY_SLOT / 2, kx + KEY_SLOT / 2, KEY_Y, KEY_Y + KEY_RISE, Z_COV - KEY_PAD - 1, D + 1)
        m -= cyl(kx, KEY_Y + KEY_RISE, Z_COV - KEY_PAD - 1, D + 1, KEY_SLOT)
    for y in (-12, -6, 0, 6, 12):
        m -= ext(rrect(60, 2.5, 1.2, 0, y), Z_COV - 1, D + 1)
    cone_top = D - HEAD_SINK
    for x, y in BOSSES:
        m -= cyl(x, y, BOSS_TOP - 1, D + 1, SCREW_FREE_D)
        m -= cyl(x, y, cone_top - (HEAD_D - SCREW_FREE_D) / 2, cone_top, SCREW_FREE_D, HEAD_D)
        m -= cyl(x, y, cone_top - 0.01, D + 1, HEAD_D + 0.2)
    return m


def to_cover_print(m):
    """Otočit kolem osy x (ne zrcadlit): záda víčka na podložce."""
    return m.rotate([180, 0, 0]).translate([0, 0, D])


def build_cover():
    return one(to_cover_print(build_cover_body()), "víčko")


def shell_to_base():
    t = np.radians(TILT)
    # (xs, ys, zs) → x, y = Y0 + zs·cos t + (ys+HH)·sin t, z = Z0 − zs·sin t + (ys+HH)·cos t
    return np.array([[1, 0, 0, 0],
                     [0, np.sin(t), np.cos(t), BODY_Y0 + HH * np.sin(t)],
                     [0, np.cos(t), -np.sin(t), BODY_Z0 + HH * np.cos(t)]], float)


def along_x(cs, x0, x1):
    """Profil v rovině (y, z) vytažený podél x."""
    return Manifold.extrude(cs, x1 - x0).transform(np.array([[0, 0, 1, x0], [1, 0, 0, 0], [0, 1, 0, 0]], float))


def build_base():
    T = shell_to_base()
    _, yf, zf = T @ [0, -HH + SLOT_DEPTH, 0, 1]
    _, yb, zb = T @ [0, -HH + SLOT_DEPTH, D, 1]
    y_lip = yb + SLOT_CLR + LIP
    side = CrossSection([[(0, 0), (FOOT_D, 0), (FOOT_D, TAIL_H), (y_lip + zb - TAIL_H, TAIL_H), (y_lip, zb), (0, zf)]])
    m = along_x(side, -BASE_HL, BASE_HL)
    xi = HL - FOOT_IN
    # mezi nožkami zůstane jen pás před tělem: kabel se pod tělem stáčí dozadu
    behind = box(-xi, xi, -HH - 50, HH + 100, -SLOT_CLR, 100).transform(T)
    m -= box(-xi, xi, BAND_T, FOOT_D + 1, -1, 100) ^ behind
    m ^= chamfered(rrect(2 * BASE_HL, FOOT_D, 4, 0, FOOT_D / 2), 0, zf + 1, CH_BASE, 0)
    m -= along_x(CrossSection([[(-1, zf - CH_TOP - 1), (CH_TOP + 2, zf + 2), (-1, zf + 2)]]), -BASE_HL - 1, BASE_HL + 1)
    slot = rrect(2 * (HL + END_CLR), 2 * (HH + SLOT_CLR), R_OUT + SLOT_CLR)
    slot += CrossSection.square([2 * (HL + END_CLR), 2 * HH]).translate([-(HL + END_CLR), 0])
    m -= ext(slot, -SLOT_CLR, D + SLOT_CLR).transform(T)
    return one(m, "podstavec")


def tip_margins():
    """Kolik mm je těžiště telefonu s tělem od přední a zadní hrany nožek (vodorovně)."""
    _, y, _ = shell_to_base() @ [0, 0, FRONT_T + PH_T / 2, 1]
    return y, FOOT_D - y


def build_tests():
    """Jeden šroubek (sloupek a kus víčka kolem něj), destička do mezery za konektorem v prvním tisku
    a konec podstavce, který se nasadí na roh těla."""
    x, y = BOSSES[2]
    crop = box(x - 6, x + 6, -HH - 1, y + 5, -1, D + 1)
    a = one(build_shell() ^ crop, "test sloupku")
    b = one(to_cover_print(build_cover_body() ^ crop), "test víčka")
    gauge = box(0, GAUGE_T, 0, 15, 0, 8)    # tloušťka leží v rovině podložky jako stěna těla
    corner = one(build_base() ^ box(HL - 4, BASE_HL + 1, -1, 24, -1, 40), "test rohu podstavce")
    return a, b, gauge, corner


def printed(m):
    """Osa z těla míří od diváka dozadu, takže bez zrcadlení by se tělo vytisklo zrcadlově (první tisk 2026-11)."""
    return m.mirror([1, 0, 0])


def mesh(m):
    mm = m.to_mesh()
    t = trimesh.Trimesh(np.asarray(mm.vert_properties)[:, :3], np.asarray(mm.tri_verts), process=True)
    assert t.is_watertight, "síť není vodotěsná"
    return t


def preview(shell, cover, base, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection

    T = shell_to_base()
    screen = box(PX0 + DISP_X0 + NOTCH_D, PX0 + DISP_X0 + DISP_L, -DISP_W / 2, DISP_W / 2, FRONT_T, FRONT_T + 0.2)
    parts = [(shell.transform(T), "#eeeeea"), (build_cover_body().transform(T), "#e2e2de"),
             (screen.transform(T), "#111111"), (base, "#2a2a2a")]
    fig = plt.figure(figsize=(14, 6.5), dpi=110)
    for k, (elev, azim, title) in enumerate([(14, -62, "na stole"), (8, -90, "zepředu"), (20, 205, "zezadu: kabel")]):
        ax = fig.add_subplot(1, 3, k + 1, projection="3d")
        for m, c in parts:
            t = mesh(m)
            ax.add_collection3d(Poly3DCollection(t.vertices[t.faces], facecolors=c, edgecolor="none",
                                                 shade=True, lightsource=matplotlib.colors.LightSource(300, 35)))
        ax.set_xlim(-100, 100); ax.set_ylim(-60, 140); ax.set_zlim(0, 200)
        ax.set_box_aspect((1, 1, 1)); ax.view_init(elev, azim); ax.set_axis_off(); ax.set_title(title)
    fig.tight_layout()
    fig.savefig(path)


def main():
    shell, cover, base = build_shell(), build_cover(), build_base()
    assert (shell ^ build_cover_body()).volume() < 1e-3, "víčko narazí do těla"
    assert (shell.transform(shell_to_base()) ^ base).volume() < 1e-3, "tělo narazí do podstavce"
    a, b, gauge, corner = build_tests()
    parts = {"test_sroub_telo": printed(a), "test_sroub_vicko": printed(b), "test_mezera": gauge, "test_roh_podstavce": corner,
             "telo": printed(shell), "vicko": printed(cover), "podstavec": base}
    stl = OUT / "stl"
    stl.mkdir(exist_ok=True)
    meshes = {}
    for name, m in parts.items():
        t = mesh(m)
        t.export(stl / f"{name}.stl")
        meshes[name] = t
        print(f"{name:12s} {t.extents[0]:6.1f} × {t.extents[1]:5.1f} × {t.extents[2]:5.1f} mm  {t.volume / 1000:6.1f} cm³")
    print(f"tělo celkem {2 * HL:.1f} × {2 * HH:.1f} × {D:.1f} mm, okénko {WW:.1f} × {WH:.1f}, "
          f"klíčové dírky {2 * KEY_X:.1f} mm od sebe")
    front, back = tip_margins()
    print(f"těžiště od přední hrany nožek {front:.1f} mm, od zadní {back:.1f} mm")

    desc = ("Drzak domaciho asistentu (stary telefon ASUS ZenFone 5Z na sirku). "
            f"Podlozka 1: test - sloupek a kus vicka sesroubovat sroubkem M2.5x10 se zapustnou hlavou; "
            f"destickou {GAUGE_T:.1f} mm zkusit mezeru za konektorem v prvnim tisku, musi vlezt volne. "
            "Podlozka 2: telo (celem dolu) a vicko (zady dolu), 4 sroubky M2.5x10 jako zadni kryt Live Display. "
            "Podlozka 3: test - konec podstavce nasadit zespodu na spodni roh tela, musi jit volne a nesmi se viklat. "
            "Podlozka 4: podstavec na stul, pas vpredu pres celou sirku tela a nozky pod konci, kabel vede pod telem dozadu mezi nozkami. "
            "Na zed: vicko ma dve klicove dirky. Potreba uhlovy kabel USB-C, kabel vychazi dole vpravo.")
    if write_3mf is None:
        print("bambu3mf chybí: 3MF zůstává z minula, nové jsou jen STL ve složce stl/")
    else:
        write_3mf(OUT / "asistent_drzak.3mf", [
            [("Test screw post", meshes["test_sroub_telo"], 1), ("Test screw cover", meshes["test_sroub_vicko"], 1),
             ("Gap gauge", meshes["test_mezera"], 1)],
            [("Screen body", meshes["telo"], 1), ("Back cover", meshes["vicko"], 1)],
            [("Stand corner test", meshes["test_roh_podstavce"], 2)],
            [("Desk stand", meshes["podstavec"], 2)],
        ], plate_names=["1 Tests - white PLA", "2 Screen body and cover - white PLA", "3 Stand corner test - black PLA",
                        "4 Desk stand - black PLA"],
            info={"Title": "Home assistant phone holder", "Designer": "Lukas Kmoch", "Description": desc},
            filaments=[("Generic PLA", "#F2F2F2"), ("Generic PLA", "#1E1E1E")])
    preview(shell, cover, base, OUT / "nahled.png")


if __name__ == "__main__":
    main()
