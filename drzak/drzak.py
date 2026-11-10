"""Držák domácího asistentu: tělo s okénkem pro telefon na šířku a sklopná nožka na zádech.

Tělo = rámeček (tiskne se čelem dolů) + víčko (tiskne se zády dolů), spojené 4 šroubky
M2,5×10 se zápustnou hlavou jako zadní kryt Live Display. Konektor drží koncová stěna těla.
Na stůl: horní dva šroubky víčka drží lištu se závěsem, na ní se vyklápí nožka (od 2026-11-10).
Na zeď: lištu s nožkou odšroubovat, víčko přišroubovat samotné a pověsit na dva šrouby.
Starý podstavec, do kterého se tělo zasune, se dál generuje jen jako STL.

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
VERSION = "v3"         # vytištěné verze jsou v VERZE.md a mají git tag drzak-vN
sys.stdout.reconfigure(encoding="utf-8")

# ---------- TELEFON (ASUS ZenFone 5Z, na šířku, výřez kamery vlevo, tlačítka nahoře) ----------
PH_L, PH_W, PH_T = 153.0, 75.65, 7.9    # výrobce, NEZMĚŘENO; tloušťka 7,7 nebo 7,9 podle zdroje, bere se větší
PHONE_G = 165.0        # výrobce
DISP_L, DISP_W, NOTCH_D = 141.0, 68.0, 5.5   # z adb: 2246 × 1080 px při 404,6 dpi
DISP_X0 = 4.0          # okraj nad displejem u výřezu, odhad. Černé pozadí nástěnky chybu ±2 mm schová
SLACK_L, SLACK_W = 0.8, 0.6   # vůle kapsy celkem
BACK_GAP = 1.5         # za telefonem: hrbol kamery se nesmí opřít o víčko
PRESS_CLR = 0.3        # žebra víčka tlačí telefon k čelu s touhle vůlí
# Za pravým koncem úhlový konektor USB-C. Stěna ho drží zasunutý: v prvním tisku (16 mm)
# za ním zbyly ~4 mm a kabel vypadával (řekl 2026-11-08). Tělo z 2026-11-08 má o 3,5 mm míň a mezera zůstala.
# Víčko musí pasovat do už vytištěného těla, takže se tohle číslo mění jen spolu s novým tělem.
PLUG_SPACE = 12.5
# Zbylých 0,5 mm do jeho 4 mm (potvrdil 2026-11-10) ubírá stěna zesílená uvnitř na obou koncích stejně,
# jak chtěl. Obrys zůstává, takže víčko z 2026-11-10 sedne do starého i nového těla.
END_PAD = 0.5

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
BODY_G = 68.0          # tělo s víčkem podle sliceru, první tisk
# Šroubky jako zadní kryt Live Display: v Ø2,5 si M2,5 vyřízne závit v PLA (zašroubováno 2026-10-17).
SCREW_LEN, SCREW_HOLE_D = 10.0, 2.5
SCREW_FREE_D = 2.8     # ve víčku volně: se závitem v obou dílech by šroub víčko od sloupku odtlačil
HEAD_D = 5.584         # hlava 90°
HEAD_SINK = 0.3        # hlava pod povrchem, první vrstva na podložce díru trochu zúží
RING_H = 1.8           # víčko má jen 1,8 mm a kužel hlavy 1,4 mm, prstenec pod ním dosedá na sloupek
CABLE_X, CABLE_W, CABLE_Z0 = 45.0, 7.0, 3.0   # otvor dole v těle z prvního tisku, víčko ho zezadu zakrývá jazýčkem
# Kabel jde spodní lištou a ven zezadu (řekl 2026-11-10). Podélný výřez: kabel se zvedá ze žlabu pozvolna.
CABLE_BACK_X0, CABLE_BACK_X1 = 52.0, 68.0
# Hlava šroubu ve zdi vjíždí až k žebru u konce telefonu, výš by o něj zavadila.
KEY_Y, KEY_HEAD, KEY_SLOT, KEY_RISE, KEY_PAD = 10.0, 8.5, 4.2, 9.0, 1.2

# ---------- NOŽKA NA ZÁDECH ----------
# Tělo stojí na spodní zadní hraně. Proti převrácení dopředu (klepnutí na displej) chrání jen sklon:
# při 15° je těžiště ~5 mm za hranou, při 30° ~18 mm.
STAND_TILT = 30.0
LEG_OPEN = 42.0        # nožka od zad; s náklonem dává nožku ~12° od svislice
LEG_W, LEG_T = 32.0, 2.5
KN_R, KN_CLR = 3.5, 0.25
# Víčko se tiskne zády na podložce, takže závěs nese samostatná lišta pod horními šroubky víčka.
BR_T, BR_X, BR_Y0, BR_Y1, BR_CH = 2.0, 67.0, 35.5, 47.0, 0.6
LUG_R, LUG_W = 4.0, 4.0
AX_Y = 40.5
HEEL_L = 6.0           # pata za osou, její plocha při otevření dosedne celá na lištu
LEG_HOLE = 7.0
PLA_G_MM3 = 1.24e-3

# ---------- PODSTAVEC (první verze, tělo do něj pořád pasuje) ----------
TILT = 15.0
BASE_L, CH_BASE = 150.0, 0.8
FOOT_W = 10.0
FOOT_D = 55.0
BODY_Y0, BODY_Z0 = 2.5, 14.0
SLOT_DEPTH, SLOT_CLR, LIP, BAR_T = 8.0, 0.3, 2.5, 2.5

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
GRILLE_X = (CX1 + HL_IN) / 2
KEY_X = (CX1 + RIB_T + HL_IN) / 2
AX_Z = D + BR_T + KN_R + 0.3    # kloub 0,3 mm nad lištou
LUG_X0 = LEG_W / 2 + KN_CLR
LUG_X1 = LUG_X0 + LUG_W


def rrect(w, h, r, cx=0.0, cy=0.0):
    r = max(min(r, w / 2 - 0.01, h / 2 - 0.01), 0.01)
    return CrossSection.square([w - 2 * r, h - 2 * r], center=True).offset(r, JoinType.Round, 2.0, 64).translate([cx, cy])


def rect(a0, a1, b0, b1):
    return CrossSection.square([a1 - a0, b1 - b0]).translate([a0, b0])


def circle(r, a, b):
    return CrossSection.circle(r, 64).translate([a, b])


def ext(cs, z0, z1):
    return Manifold.extrude(cs, z1 - z0).translate([0, 0, z0])


def prism_x(cs, x0, x1):
    """Profil v rovině (y, z) vytažený podél osy x."""
    return Manifold.extrude(cs, x1 - x0).transform(np.array([[0, 0, 1, x0], [1, 0, 0, 0], [0, 1, 0, 0]], float))


def box(x0, x1, y0, y1, z0, z1):
    return Manifold.cube([x1 - x0, y1 - y0, z1 - z0]).translate([x0, y0, z0])


def cyl(x, y, z0, z1, d, d_top=None):
    return Manifold.cylinder(z1 - z0, d / 2, (d_top or d) / 2, 48).translate([x, y, z0])


def countersink(x, y, z0, top):
    """Volná díra od z0 a kužel hlavy, který končí na výšce top."""
    return (cyl(x, y, z0, top + 2, SCREW_FREE_D)
            + cyl(x, y, top - (HEAD_D - SCREW_FREE_D) / 2, top, SCREW_FREE_D, HEAD_D)
            + cyl(x, y, top - 0.01, top + 2, HEAD_D + 0.2))


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
    z0, z1 = FRONT_T - 0.3, Z_COV - KEY_PAD - 0.2    # pod podložkami klíčových dírek, telefon končí na 9,9
    m -= ext(rrect(2 * HL_IN, 2 * HH_IN, R_OUT - WALL), z1, D + 1)
    m -= ext(rrect(2 * (HL_IN - END_PAD), 2 * HH_IN, R_OUT - WALL), FRONT_T, z1 + 0.01)
    m -= Manifold.batch_hull([ext(rrect(WW + 2 * WIN_CH, WH + 2 * WIN_CH, WIN_R + WIN_CH), -1, 0),
                              ext(rrect(WW, WH, WIN_R), FRONT_T, FRONT_T + 1)])
    # Zvuk reproduktoru a mikrofonu musí ven dopředu, štěrbiny ve víčku míří od posluchače (řekl 2026-11-10).
    for sx in (-1, 1):
        for i in (-1, 0, 1):
            for j in range(-3, 4):
                m -= cyl(sx * GRILLE_X + i * GRILLE_PITCH, j * GRILLE_PITCH, -1, FRONT_T + 1, GRILLE_D)

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
    # Zvuk z konců ven zezadu, aby nové čelo mohlo být bez teček (řekl 2026-11-09).
    for sx in (-1, 1):
        for y in (-6, -11, -16, -21, -26):
            m -= ext(rrect(7.5, 2.0, 1.0, sx * KEY_X, y), Z_COV - 1, D + 1)
    m -= ext(rrect(CABLE_BACK_X1 - CABLE_BACK_X0, 2 * (HH + CY0 - RIB_T), 2.5,
                   (CABLE_BACK_X0 + CABLE_BACK_X1) / 2, -HH), Z_COV - 1, D + 1)
    for x, y in BOSSES:
        m -= countersink(x, y, BOSS_TOP - 1, D - HEAD_SINK)
    return m


def to_cover_print(m):
    """Otočit kolem osy x (ne zrcadlit): záda víčka na podložce."""
    return m.rotate([180, 0, 0]).translate([0, 0, D])


def build_cover():
    return one(to_cover_print(build_cover_body()), "víčko")


def build_bracket():
    """Lišta se dvěma oky závěsu, v souřadnicích těla (leží na zádech víčka)."""
    m = chamfered(rrect(2 * BR_X, BR_Y1 - BR_Y0, 2.0, 0, (BR_Y0 + BR_Y1) / 2), D, D + BR_T, 0, BR_CH)
    lug = CrossSection.batch_hull([circle(LUG_R, AX_Y, AX_Z), rect(AX_Y - LUG_R, AX_Y + LUG_R, D, D + BR_T)])
    r = SCREW_FREE_D / 2
    # Díra leží vodorovně: kapka se špičkou nahoru, strop pak nepotřebuje podpěru.
    drop = CrossSection.batch_hull([circle(r, AX_Y, AX_Z), rect(AX_Y - 0.01, AX_Y + 0.01, AX_Z, AX_Z + r * 1.414)])
    cone_x0 = LUG_X1 - HEAD_SINK - (HEAD_D - SCREW_FREE_D) / 2
    cone = Manifold.cylinder(LUG_X1 + 0.5 - cone_x0, r, r + LUG_X1 + 0.5 - cone_x0, 48)
    cone = cone.rotate([0, 90, 0]).translate([cone_x0, AX_Y, AX_Z])
    half = prism_x(lug, LUG_X0, LUG_X1) - prism_x(drop, LUG_X0 - 1, LUG_X1 + 1) - cone
    m += half + half.mirror([1, 0, 0])
    for x, y in BOSSES[:2]:
        m -= countersink(x, y, D - 1, D + BR_T - HEAD_SINK)
    return one(m, "lišta")


def heel_contact():
    """Kde (y) pata leží na liště, když je nožka otevřená."""
    a = AX_Z - (D + BR_T)
    s, c = np.sin(np.radians(LEG_OPEN)), np.cos(np.radians(LEG_OPEN))
    u1 = (a - KN_R * c) / s
    v2 = (HEEL_L * s - a) / c
    return a, u1, v2, [AX_Y + u1 * c - KN_R * s, AX_Y + HEEL_L * c + v2 * s]


def leg_profile():
    """Nožka zaklapnutá, profil v rovině (y, z); dlouhá, zkrátí ji až rovina stolu."""
    a, u1, v2, _ = heel_contact()
    s, c = np.sin(np.radians(LEG_OPEN)), np.cos(np.radians(LEG_OPEN))
    z_in = AX_Z - KN_R
    slab = rect(AX_Y - 120, AX_Y, z_in, z_in + LEG_T)
    ramp = CrossSection.batch_hull([circle(KN_R, AX_Y, AX_Z), rect(AX_Y - 10, AX_Y, z_in, z_in + LEG_T)])
    heel = CrossSection([[(0, -KN_R), (u1, -KN_R), (HEEL_L, v2), (HEEL_L, 2.5), (0, KN_R)]]).translate([AX_Y, AX_Z])
    # Roh paty by se při otevírání dostal pod rovinu lišty dřív než celá plocha a drhl by.
    wedge = CrossSection([[(0, 0), (0, -10), (10 * s, -10 * c)]]) - CrossSection.circle(a, 128)
    return slab + ramp + heel - wedge.translate([AX_Y, AX_Z])


def hinge_turn(m, deg):
    """Otočit nožku kolem osy závěsu; kladně = špička od zad."""
    return m.translate([0, -AX_Y, -AX_Z]).rotate([-deg, 0, 0]).translate([0, AX_Y, AX_Z])


def stand_matrix(shell):
    """Tělo → stůl (x, y dozadu, z nahoru), spodní zadní hrana těla na y = z = 0."""
    t = np.radians(STAND_TILT)
    R = np.array([[1, 0, 0], [0, np.sin(t), np.cos(t)], [0, np.cos(t), -np.sin(t)]])
    v = np.asarray(shell.to_mesh().vert_properties)[:, :3] @ R.T
    low = v[np.argmin(v[:, 2])]
    return np.hstack([R, [[0], [-low[1]], [-low[2]]]])


def inverse(T):
    R = T[:, :3].T
    return np.hstack([R, (-R @ T[:, 3])[:, None]])


def build_leg(T):
    m = prism_x(leg_profile(), -LEG_W / 2, LEG_W / 2)
    m = hinge_turn(hinge_turn(m, LEG_OPEN).transform(T).trim_by_plane([0, 0, 1], 0.0).transform(inverse(T)), -LEG_OPEN)
    hole = prism_x(circle(SCREW_HOLE_D / 2, AX_Y, AX_Z), LEG_W / 2 - LEG_HOLE, LEG_W / 2 + 1)
    m -= hole + hole.mirror([1, 0, 0])
    return one(m, "nožka")


def check_hinge(shell, cover_b, bracket, leg):
    fixed = shell + cover_b + bracket
    assert (bracket ^ (shell + cover_b)).volume() < 1e-3, "lišta narazí do těla nebo víčka"
    for deg in [*np.arange(0, LEG_OPEN, 3.0), LEG_OPEN - 0.5]:
        assert (hinge_turn(leg, deg) ^ fixed).volume() < 1e-3, f"nožka drhne při {deg:.1f}°"
    assert (hinge_turn(leg, LEG_OPEN + 2) ^ bracket).volume() > 0.5, "pata nožku nezastaví"
    y0, y1 = heel_contact()[3]
    assert BR_Y0 + BR_CH <= y0 and y1 <= BR_Y1 - BR_CH, f"pata dosedá mimo rovnou plochu lišty ({y0:.1f}–{y1:.1f})"
    assert leg.bounding_box()[1] >= -HH_IN + 0.5, "zaklapnutá nožka vyčnívá pod tělo"


def stand_margins(T, shell, cover_b, bracket, leg):
    """Těžiště za spodní hranou těla a před patkou nožky, vodorovně v mm."""
    def com(m):
        return mesh(m.transform(T)).center_mass
    leg_open = hinge_turn(leg, LEG_OPEN)
    items = [(PHONE_G, T[:, :3] @ [PX0 + PH_L / 2, 0, FRONT_T + PH_T / 2] + T[:, 3]),
             (BODY_G, com(shell + cover_b))]
    items += [(m.volume() * PLA_G_MM3, com(m)) for m in (bracket, leg_open)]
    y = sum(g * p[1] for g, p in items) / sum(g for g, _ in items)
    v = np.asarray(leg_open.transform(T).to_mesh().vert_properties)[:, :3]
    return y, v[v[:, 2] < 0.01, 1].min() - y


def build_hinge_test(bracket, leg):
    """Kus lišty s jedním okem a kus nožky s kloubem a patou, spojí je jeden šroubek."""
    lug = one(bracket ^ box(9, LUG_X1 + 1.5, BR_Y0 - 1, BR_Y1 + 1, D - 1, AX_Z + 10), "test oka")
    knuckle = one(leg ^ box(9, LEG_W / 2 + 1, AX_Y - 12, AX_Y + 10, 0, 40), "test kloubu")
    return lug, knuckle


def to_side_print(m):
    """Nožka na boku: díry pro šroubky svisle, profil přesně v rovině podložky."""
    m = m.rotate([0, 90, 0])
    return m.translate([0, 0, -m.bounding_box()[2]])


def shell_to_base():
    t = np.radians(TILT)
    # (xs, ys, zs) → x, y = Y0 + zs·cos t + (ys+HH)·sin t, z = Z0 − zs·sin t + (ys+HH)·cos t
    return np.array([[1, 0, 0, 0],
                     [0, np.sin(t), np.cos(t), BODY_Y0 + HH * np.sin(t)],
                     [0, np.cos(t), -np.sin(t), BODY_Z0 + HH * np.cos(t)]], float)


def build_base():
    T = shell_to_base()
    _, yf, zf = T @ [0, -HH + SLOT_DEPTH, 0, 1]
    _, yb, zb = T @ [0, -HH + SLOT_DEPTH, D, 1]
    side = CrossSection([[(0, 0), (FOOT_D, 0), (FOOT_D, 3), (yb + SLOT_CLR + LIP, zb), (0, zf)]])
    m = box(-BASE_L / 2, BASE_L / 2, 0, BAR_T, 0, BODY_Z0 - 0.5)    # zakryje kabel pod tělem zepředu
    for x0 in (-BASE_L / 2, BASE_L / 2 - FOOT_W):
        m += prism_x(side, x0, x0 + FOOT_W)
    m ^= chamfered(rrect(BASE_L, FOOT_D, 3, 0, FOOT_D / 2), 0, zf + 1, CH_BASE, 0)
    m -= box(-BASE_L, BASE_L, -HH - SLOT_CLR, -HH + 80, -SLOT_CLR, D + SLOT_CLR).transform(T)
    return one(m, "podstavec")


def printed(m):
    """Osa z těla míří od diváka dozadu, takže bez zrcadlení by se tělo vytisklo zrcadlově (první tisk 2026-11)."""
    return m.mirror([1, 0, 0])


def mesh(m):
    mm = m.to_mesh()
    t = trimesh.Trimesh(np.asarray(mm.vert_properties)[:, :3], np.asarray(mm.tri_verts), process=True)
    assert t.is_watertight, "síť není vodotěsná"
    return t


def preview(T, shell, cover_b, bracket, leg, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection

    screen = box(PX0 + DISP_X0 + NOTCH_D, PX0 + DISP_X0 + DISP_L, -DISP_W / 2, DISP_W / 2, FRONT_T, FRONT_T + 0.2)
    back = [(hinge_turn(leg, LEG_OPEN), "#c8c8c2"), (bracket, "#d4d4cf"), (cover_b, "#e2e2de")]
    front = [(shell, "#eeeeea"), (screen, "#111111")]
    # matplotlib nemá hloubkový buffer: díly se kreslí odzadu dopředu podle pohledu
    views = [(14, -62, "na stole", back + front), (0, 0, "z boku", front + back), (22, 125, "zezadu", front[:1] + back[::-1])]
    fig = plt.figure(figsize=(14, 6.5), dpi=110)
    for k, (elev, azim, title, parts) in enumerate(views):
        ax = fig.add_subplot(1, 3, k + 1, projection="3d", computed_zorder=False)
        for m, c in parts:
            t = mesh(m.transform(T))
            ax.add_collection3d(Poly3DCollection(t.vertices[t.faces], facecolors=c, edgecolor="none",
                                                 shade=True, lightsource=matplotlib.colors.LightSource(300, 35)))
        ax.set_xlim(-100, 100); ax.set_ylim(-60, 140); ax.set_zlim(0, 200)
        ax.set_box_aspect((1, 1, 1)); ax.view_init(elev, azim); ax.set_axis_off(); ax.set_title(title)
    fig.tight_layout()
    fig.savefig(path)


def main():
    shell, cover_b, base = build_shell(), build_cover_body(), build_base()
    T = stand_matrix(shell)
    bracket, leg = build_bracket(), build_leg(T)
    assert (shell ^ cover_b).volume() < 1e-3, "víčko narazí do těla"
    assert (shell.transform(shell_to_base()) ^ base).volume() < 1e-3, "tělo narazí do podstavce"
    check_hinge(shell, cover_b, bracket, leg)
    lug, knuckle = build_hinge_test(bracket, leg)
    parts = {"test_zaves_oko": printed(lug.translate([0, 0, -D])),
             "test_zaves_kloub": to_side_print(printed(knuckle)),
             "vicko": printed(build_cover()),
             "lista": printed(bracket.translate([0, 0, -D])),
             "nozka": to_side_print(printed(leg)),
             "telo": printed(shell), "podstavec": base}
    stl = OUT / "stl"
    stl.mkdir(exist_ok=True)
    meshes = {}
    for name, m in parts.items():
        t = mesh(m)
        t.export(stl / f"{name}.stl")
        meshes[name] = t
        print(f"{name:16s} {t.extents[0]:6.1f} × {t.extents[1]:5.1f} × {t.extents[2]:5.1f} mm  {t.volume / 1000:6.1f} cm³")
    print(f"tělo celkem {2 * HL:.1f} × {2 * HH:.1f} × {D:.1f} mm, okénko {WW:.1f} × {WH:.1f}")
    front, back = stand_margins(T, shell, cover_b, bracket, leg)
    _, _, _, (h0, h1) = heel_contact()
    print(f"na nožce: sklon {STAND_TILT:.0f}°, těžiště {front:.1f} mm za spodní hranou těla, {back:.1f} mm před patkou; "
          f"pata dosedá na lištu v y {h0:.1f}–{h1:.1f}, zaklapnutá nožka končí na y {leg.bounding_box()[1]:.1f}")

    desc = (f"Drzak domaciho asistentu (ASUS ZenFone 5Z na sirku), verze {VERSION}. "
            "Podlozka 1: test zavesu. Kus listy s okem a kus nozky spojit sroubkem M2.5x10 skrz oko do nozky. "
            f"Sroubek dotahnout jen tak, aby se nozka jeste otacela; musi se zastavit na {LEG_OPEN:.0f} stupnich. "
            "Podlozka 2: nove vicko (kabel vychazi dozadu vyrezem dole vpravo, zvuk z koncu otvory vzadu), "
            "lista se zavesem a nozka. Lista se prisroubuje hornimi dvema sroubky vicka, nozka dvema sroubky "
            "M2.5x10 z boku skrz oka. Celkem 6 sroubku. Na zed: listu odsroubovat, vicko prisroubovat samotne. "
            "Podlozka 3: nove celo cerne, s teckami na zvuk, konce uvnitr o 4 mm kratsi nez prvni tisk, vicko z podlozky 2 do nej pasuje.")
    if write_3mf is None:
        print("bambu3mf chybí: 3MF zůstává z minula, nové jsou jen STL ve složce stl/")
    else:
        brim = {"brim_type": "outer_only"}    # nožka stojí na boku: 32 mm vysoká a 2,5 mm tenká
        write_3mf(OUT / "asistent_drzak.3mf", [
            [("Test hinge lug", meshes["test_zaves_oko"], 1), ("Test hinge knuckle", meshes["test_zaves_kloub"], 1)],
            [("Back cover", meshes["vicko"], 1), ("Hinge bracket", meshes["lista"], 1),
             ("Kickstand", meshes["nozka"], 1, brim)],
            [("Front frame", meshes["telo"], 2)],
        ], plate_names=["1 Hinge test - white PLA", "2 Back cover and kickstand - white PLA", "3 Front frame - black PLA"],
            info={"Title": f"Home assistant phone holder {VERSION}", "Designer": "Lukas Kmoch", "Description": desc},
            filaments=[("Generic PLA", "#F2F2F2"), ("Generic PLA", "#1A1A1A")])
    preview(T, shell, cover_b, bracket, leg, OUT / "nahled.png")


if __name__ == "__main__":
    main()
