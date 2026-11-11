"""Home assistant phone holder: a body with a landscape phone window and a fold-out kickstand on the back.

Body = frame (printed face down) + cover (printed back down), joined by 4 M2.5x10 countersunk
screws like the Live Display back cover. The end wall of the body holds the connector.
On a desk: the top two cover screws hold a rail with a hinge, and the kickstand folds out of it (since 2026-11-10).
On a wall: unscrew the rail with the kickstand, screw the cover on alone and hang it on two screws.
The old stand the body slides into is still generated, as STL only.

Run: python holder.py -> stl/*.stl, holder.3mf, preview.png
Body coordinates: x horizontal (window centre = 0), y up, z from the face (0) towards the back.
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
VERSION = "v5"         # printed versions are in VERSIONS.md and have the git tag holder-vN
sys.stdout.reconfigure(encoding="utf-8")

# ---------- PHONE (ASUS ZenFone 5Z, landscape, camera notch on the left, buttons on top) ----------
PH_L, PH_W, PH_T = 153.0, 75.65, 7.9    # manufacturer, NOT MEASURED; thickness 7.7 or 7.9 depending on source, the larger one is used
PHONE_G = 165.0        # manufacturer
DISP_L, DISP_W, NOTCH_D = 141.0, 68.0, 5.5   # from adb: 2246 × 1080 px at 404.6 dpi
DISP_X0 = 4.0          # bezel above the display at the notch, estimate. The black dashboard background hides a ±2 mm error
SLACK_L, SLACK_W = 0.8, 0.6   # pocket clearance, total
BACK_GAP = 1.5         # behind the phone: the camera bump must not rest on the cover
PRESS_CLR = 0.3        # cover ribs press the phone to the face with this clearance
# Right-angle USB-C connector behind the right end. The wall holds it in: the first print (16 mm)
# left ~4 mm behind it and the cable fell out (he said 2026-11-08). The 2026-11-08 body is 3.5 mm shorter and the gap remained.
# 5 mm shorter in total than the first print, equally at both ends: 4 mm confirmed 2026-11-10, v3 face still had a gap,
# another 1 mm (he said 2026-11-11). The whole box and the cover get shorter; the v2 cover does not fit it.
PLUG_SPACE = 11.0

# ---------- BODY ----------
FRONT_T, WALL, COVER_T = 1.2, 2.0, 1.8   # face like Live Display (1.0), dots shallow and clean
HH = 48.0              # half the body height: frame above and below the window ~15 mm, so it does not look like a phone
R_OUT = 10.0
CH_FRONT, CH_BACK = 1.5, 0.6
WIN_MARGIN, WIN_R, WIN_CH = 1.0, 4.0, 1.0   # window 1 mm inside the display; a chamfer wider than FRONT_T would be an overhang over 45°
RIB_T = 1.6
STOP_LEN = 12.0        # top stops only at the ends: the buttons are top centre
# Dots without chamfer: the v3 slots with a chamfered edge came out 3 mm wide on the face with 0.4 mm ribs, printed ugly.
GRILLE_D, GRILLE_PITCH = 2.0, 4.0
BOSS_D = 6.5
COV_CLR = 0.25
BODY_G = 68.0          # body with cover per slicer, first print
# Screws like the Live Display back cover: M2.5 cuts its own thread in a Ø2.5 hole in PLA (screwed in 2026-10-17).
SCREW_LEN, SCREW_HOLE_D = 10.0, 2.5
SCREW_FREE_D = 2.8     # free in the cover: with a thread in both parts the screw would push the cover off the post
HEAD_D = 5.584         # 90° head
HEAD_SINK = 0.3        # head below the surface, the first layer on the bed narrows the hole a little
RING_H = 1.8           # the cover is only 1.8 mm and the head cone 1.4 mm, the ring under it rests on the post
CABLE_X, CABLE_W, CABLE_Z0 = 45.0, 7.0, 3.0   # opening at the bottom of the body from the first print, the cover hides it from behind with a tab
# The cable runs along the bottom rail and out the back (he said 2026-11-10). Longitudinal cut-out: the cable rises gently from the channel.
CABLE_BACK_X0, CABLE_BACK_X1 = 52.0, 68.0
# The wall screw head slides in up to the rib at the end of the phone, higher it would catch on it.
KEY_Y, KEY_HEAD, KEY_SLOT, KEY_RISE, KEY_PAD = 10.0, 8.5, 4.2, 9.0, 1.2

# ---------- KICKSTAND ON THE BACK ----------
# The body stands on its bottom back edge. Only the tilt protects against tipping forward (tapping the display):
# at 15° the centre of mass is ~5 mm behind the edge, at 30° ~18 mm.
STAND_TILT = 30.0
LEG_OPEN = 42.0        # leg from the back; with the tilt this puts the leg ~12° from vertical
LEG_W, LEG_T = 32.0, 2.5
KN_R, KN_CLR = 3.5, 0.25
# The cover is printed back down, so the hinge is carried by a separate rail under the cover's top screws.
BR_T, BR_X, BR_Y0, BR_Y1, BR_CH = 2.0, 67.0, 35.5, 47.0, 0.6
LUG_R, LUG_W = 4.0, 4.0
AX_Y = 40.5
HEEL_L = 6.0           # heel behind the axis, its whole surface lands flat on the rail when open
LEG_HOLE = 7.0
PLA_G_MM3 = 1.24e-3

# ---------- STAND (first version, the body still fits it) ----------
TILT = 15.0
BASE_L, CH_BASE = 150.0, 0.8
FOOT_W = 10.0
FOOT_D = 55.0
BODY_Y0, BODY_Z0 = 2.5, 14.0
SLOT_DEPTH, SLOT_CLR, LIP, BAR_T = 8.0, 0.3, 2.5, 2.5

# ---------- DERIVED ----------
WW = DISP_L - NOTCH_D - 2 * WIN_MARGIN
WH = DISP_W - 2 * WIN_MARGIN
PX0 = -(DISP_X0 + NOTCH_D + WIN_MARGIN + WW / 2)    # left end of the phone
CX0, CX1 = PX0 - SLACK_L / 2, PX0 + PH_L + SLACK_L / 2
CY1 = (PH_W + SLACK_W) / 2
CY0 = -CY1
HL_IN = CX1 + PLUG_SPACE        # symmetric: the same free room on the left as on the right for the connector
HL = HL_IN + WALL
HH_IN = HH - WALL
D = FRONT_T + PH_T + BACK_GAP + COVER_T
Z_COV = D - COVER_T
BOSS_Y = HH_IN - COV_CLR - 0.5 - (HEAD_D + 0.2) / 2   # the screw head must stay entirely in the cover
BOSSES = [(-62.0, BOSS_Y), (62.0, BOSS_Y), (-62.0, -BOSS_Y), (20.0, -BOSS_Y)]   # bottom right is where the cable runs
BOSS_TOP = Z_COV - RING_H
HOLE_BOTTOM = D - HEAD_SINK - SCREW_LEN - 0.5
assert HOLE_BOTTOM >= FRONT_T + 0.3, "screw would break through the face"
assert BOSS_Y - BOSS_D / 2 >= CY1 + 0.9, "post intrudes into the phone"
# The phone is off-centre, so each end's dots go to the middle of its free space, so the left ones do not reach over the phone.
GRILLE_XS = ((CX0 - HL_IN) / 2, (CX1 + HL_IN) / 2)
# Wall screw spacing a round 162 mm (he wanted it 2026-11-11). 165 does not work: in v4 only 0.2 mm was left to the cover edge.
# 160 does not work: the head would rest on the end of the phone behind the cover.
KEY_X = 81.0
assert KEY_X + KEY_HEAD / 2 + 1.2 <= HL_IN - COV_CLR, "keyhole at the cover edge"
assert KEY_X - KEY_HEAD / 2 >= CX1 + 0.5, "wall screw head hits the phone"
assert np.hypot(KEY_X - CX1 - RIB_T, KEY_Y + KEY_RISE - (CY1 - 15)) > KEY_HEAD / 2 + 0.3, "wall screw head catches on the rib"
# The wall screw head slides behind the cover under its pad, up to the end wall.
assert KEY_X + KEY_HEAD / 2 + 0.3 <= HL_IN, "wall screw head hits the end wall"
AX_Z = D + BR_T + KN_R + 0.3    # hinge 0.3 mm above the rail
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
    """Profile in the (y, z) plane extruded along the x axis."""
    return Manifold.extrude(cs, x1 - x0).transform(np.array([[0, 0, 1, x0], [1, 0, 0, 0], [0, 1, 0, 0]], float))


def box(x0, x1, y0, y1, z0, z1):
    return Manifold.cube([x1 - x0, y1 - y0, z1 - z0]).translate([x0, y0, z0])


def cyl(x, y, z0, z1, d, d_top=None):
    return Manifold.cylinder(z1 - z0, d / 2, (d_top or d) / 2, 48).translate([x, y, z0])


def countersink(x, y, z0, top):
    """Free hole from z0 and the head cone ending at height top."""
    return (cyl(x, y, z0, top + 2, SCREW_FREE_D)
            + cyl(x, y, top - (HEAD_D - SCREW_FREE_D) / 2, top, SCREW_FREE_D, HEAD_D)
            + cyl(x, y, top - 0.01, top + 2, HEAD_D + 0.2))


def chamfered(cs, z0, z1, ch0, ch1):
    """Convex outline with a chamfer at the bottom and top (45°)."""
    parts = [ext(cs, z0 + ch0, z1 - ch1)]
    if ch0:
        parts.append(ext(cs.offset(-ch0, JoinType.Round, 2.0, 64), z0, z0 + 0.01))
    if ch1:
        parts.append(ext(cs.offset(-ch1, JoinType.Round, 2.0, 64), z1 - 0.01, z1))
    return Manifold.batch_hull(parts)


def one(m, name):
    assert len(m.decompose()) == 1, f"{name}: multiple pieces ({len(m.decompose())})"
    return m


def build_shell():
    m = chamfered(rrect(2 * HL, 2 * HH, R_OUT), 0, D, CH_FRONT, CH_BACK)
    z0, z1 = FRONT_T - 0.3, Z_COV - KEY_PAD - 0.2    # below the keyhole pads, the phone ends at 9.9
    m -= ext(rrect(2 * HL_IN, 2 * HH_IN, R_OUT - WALL), z1, D + 1)
    m -= ext(rrect(2 * HL_IN, 2 * HH_IN, R_OUT - WALL), FRONT_T, z1 + 0.01)
    m -= Manifold.batch_hull([ext(rrect(WW + 2 * WIN_CH, WH + 2 * WIN_CH, WIN_R + WIN_CH), -1, 0),
                              ext(rrect(WW, WH, WIN_R), FRONT_T, FRONT_T + 1)])
    # Speaker and microphone sound must come out the front, slots in the cover point away from the listener (he said 2026-11-10).
    for gx in GRILLE_XS:
        for i in (-1, 0, 1):
            for j in range(-3, 4):
                m -= cyl(gx + i * GRILLE_PITCH, j * GRILLE_PITCH, -1, FRONT_T + 1, GRILLE_D)

    feats = [
        box(CX0, CX1, CY0 - RIB_T, CY0, z0, z1),                              # bottom, full length
        box(CX0 - RIB_T, CX0, -HH_IN - 0.5, CY0 + 15, z0, z1),                 # left bottom
        box(CX0 - RIB_T, CX0, CY1 - 15, HH_IN + 0.5, z0, z1),                  # left top
        box(CX0 - RIB_T, CX0 + STOP_LEN, CY1, HH_IN + 0.5, z0, z1),            # top left
        box(CX1 - STOP_LEN, CX1 + RIB_T, CY1, HH_IN + 0.5, z0, z1),            # top right
        box(CX1, CX1 + RIB_T, CY1 - 15, HH_IN + 0.5, z0, z1),                  # right top; the cable runs at the bottom
    ]
    feats += [cyl(x, y, z0, BOSS_TOP, BOSS_D) for x, y in BOSSES]
    m += sum(feats[1:], feats[0])
    room = ext(rrect(2 * HL_IN, 2 * HH_IN, R_OUT - WALL), BOSS_TOP, D + 1)
    for x, y in BOSSES:
        m -= cyl(x, y, BOSS_TOP, D + 1, BOSS_D + 0.6) ^ room    # room for the cover ring
        m -= cyl(x, y, HOLE_BOTTOM, BOSS_TOP + 1, SCREW_HOLE_D)
    m -= box(CABLE_X - CABLE_W / 2, CABLE_X + CABLE_W / 2, -HH - 1, -HH_IN + 0.01, CABLE_Z0, D + 1)
    m -= box(-6, 6, HH_IN - 0.5, HH + 1, D - 1.5, D + 1)        # fingernail notch, the cover can be pried off
    return one(m, "body")


def build_cover_body():
    """Cover in body coordinates."""
    outline = rrect(2 * (HL_IN - COV_CLR), 2 * (HH_IN - COV_CLR), R_OUT - WALL - COV_CLR)
    m = ext(outline, Z_COV, D)
    m += box(CABLE_X - CABLE_W / 2 + 0.2, CABLE_X + CABLE_W / 2 - 0.2, -HH + CH_BACK, -HH_IN + 0.5, Z_COV, D)
    zf = Z_COV + 0.3
    for x, y in BOSSES:
        m += cyl(x, y, BOSS_TOP, zf, BOSS_D) ^ ext(outline, BOSS_TOP, zf)
    # presses the phone to the face along the long edges; at the top only behind the camera
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
    # Sound from the ends goes out the back so the new face can be without dots (he said 2026-11-09).
    for sx in (-1, 1):
        for y in (-6, -11, -16, -21, -26):
            m -= ext(rrect(7.5, 2.0, 1.0, sx * KEY_X, y), Z_COV - 1, D + 1)
    m -= ext(rrect(CABLE_BACK_X1 - CABLE_BACK_X0, 2 * (HH + CY0 - RIB_T), 2.5,
                   (CABLE_BACK_X0 + CABLE_BACK_X1) / 2, -HH), Z_COV - 1, D + 1)
    for x, y in BOSSES:
        m -= countersink(x, y, BOSS_TOP - 1, D - HEAD_SINK)
    return m


def to_cover_print(m):
    """Rotate about the x axis (do not mirror): cover back on the bed."""
    return m.rotate([180, 0, 0]).translate([0, 0, D])


def build_cover():
    return one(to_cover_print(build_cover_body()), "cover")


def build_bracket():
    """Rail with two hinge lugs, in body coordinates (lies on the back of the cover)."""
    m = chamfered(rrect(2 * BR_X, BR_Y1 - BR_Y0, 2.0, 0, (BR_Y0 + BR_Y1) / 2), D, D + BR_T, 0, BR_CH)
    lug = CrossSection.batch_hull([circle(LUG_R, AX_Y, AX_Z), rect(AX_Y - LUG_R, AX_Y + LUG_R, D, D + BR_T)])
    r = SCREW_FREE_D / 2
    # The hole lies horizontal: a teardrop with the tip up, so the ceiling needs no support.
    drop = CrossSection.batch_hull([circle(r, AX_Y, AX_Z), rect(AX_Y - 0.01, AX_Y + 0.01, AX_Z, AX_Z + r * 1.414)])
    cone_x0 = LUG_X1 - HEAD_SINK - (HEAD_D - SCREW_FREE_D) / 2
    cone = Manifold.cylinder(LUG_X1 + 0.5 - cone_x0, r, r + LUG_X1 + 0.5 - cone_x0, 48)
    cone = cone.rotate([0, 90, 0]).translate([cone_x0, AX_Y, AX_Z])
    half = prism_x(lug, LUG_X0, LUG_X1) - prism_x(drop, LUG_X0 - 1, LUG_X1 + 1) - cone
    m += half + half.mirror([1, 0, 0])
    for x, y in BOSSES[:2]:
        m -= countersink(x, y, D - 1, D + BR_T - HEAD_SINK)
    return one(m, "rail")


def heel_contact():
    """Where (y) the heel lies on the rail when the leg is open."""
    a = AX_Z - (D + BR_T)
    s, c = np.sin(np.radians(LEG_OPEN)), np.cos(np.radians(LEG_OPEN))
    u1 = (a - KN_R * c) / s
    v2 = (HEEL_L * s - a) / c
    return a, u1, v2, [AX_Y + u1 * c - KN_R * s, AX_Y + HEEL_L * c + v2 * s]


def leg_profile():
    """Leg latched, profile in the (y, z) plane; long, only the desk plane shortens it."""
    a, u1, v2, _ = heel_contact()
    s, c = np.sin(np.radians(LEG_OPEN)), np.cos(np.radians(LEG_OPEN))
    z_in = AX_Z - KN_R
    slab = rect(AX_Y - 120, AX_Y, z_in, z_in + LEG_T)
    ramp = CrossSection.batch_hull([circle(KN_R, AX_Y, AX_Z), rect(AX_Y - 10, AX_Y, z_in, z_in + LEG_T)])
    heel = CrossSection([[(0, -KN_R), (u1, -KN_R), (HEEL_L, v2), (HEEL_L, 2.5), (0, KN_R)]]).translate([AX_Y, AX_Z])
    # The heel corner would dip below the rail plane before the whole surface does when opening, and would drag.
    wedge = CrossSection([[(0, 0), (0, -10), (10 * s, -10 * c)]]) - CrossSection.circle(a, 128)
    return slab + ramp + heel - wedge.translate([AX_Y, AX_Z])


def hinge_turn(m, deg):
    """Rotate the leg about the hinge axis; positive = tip away from the back."""
    return m.translate([0, -AX_Y, -AX_Z]).rotate([-deg, 0, 0]).translate([0, AX_Y, AX_Z])


def stand_matrix(shell):
    """Body -> desk (x, y backwards, z up), the bottom back edge of the body at y = z = 0."""
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
    return one(m, "leg")


def check_hinge(shell, cover_b, bracket, leg):
    fixed = shell + cover_b + bracket
    assert (bracket ^ (shell + cover_b)).volume() < 1e-3, "rail hits the body or cover"
    for deg in [*np.arange(0, LEG_OPEN, 3.0), LEG_OPEN - 0.5]:
        assert (hinge_turn(leg, deg) ^ fixed).volume() < 1e-3, f"leg drags at {deg:.1f}°"
    assert (hinge_turn(leg, LEG_OPEN + 2) ^ bracket).volume() > 0.5, "heel does not stop the leg"
    y0, y1 = heel_contact()[3]
    assert BR_Y0 + BR_CH <= y0 and y1 <= BR_Y1 - BR_CH, f"heel lands off the flat surface of the rail ({y0:.1f}–{y1:.1f})"
    assert leg.bounding_box()[1] >= -HH_IN + 0.5, "latched leg sticks out below the body"


def stand_margins(T, shell, cover_b, bracket, leg):
    """Centre of mass behind the bottom edge of the body and in front of the leg foot, horizontal in mm."""
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
    """A piece of rail with one lug and a piece of leg with knuckle and heel, joined by one screw."""
    lug = one(bracket ^ box(9, LUG_X1 + 1.5, BR_Y0 - 1, BR_Y1 + 1, D - 1, AX_Z + 10), "lug test")
    knuckle = one(leg ^ box(9, LEG_W / 2 + 1, AX_Y - 12, AX_Y + 10, 0, 40), "knuckle test")
    return lug, knuckle


def to_side_print(m):
    """Leg on its side: screw holes vertical, profile exactly in the bed plane."""
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
    m = box(-BASE_L / 2, BASE_L / 2, 0, BAR_T, 0, BODY_Z0 - 0.5)    # hides the cable under the body from the front
    for x0 in (-BASE_L / 2, BASE_L / 2 - FOOT_W):
        m += prism_x(side, x0, x0 + FOOT_W)
    m ^= chamfered(rrect(BASE_L, FOOT_D, 3, 0, FOOT_D / 2), 0, zf + 1, CH_BASE, 0)
    m -= box(-BASE_L, BASE_L, -HH - SLOT_CLR, -HH + 80, -SLOT_CLR, D + SLOT_CLR).transform(T)
    return one(m, "stand")


def printed(m):
    """The body z axis points from the viewer backwards, so without mirroring the body would print mirrored (first print 2026-11)."""
    return m.mirror([1, 0, 0])


def mesh(m):
    mm = m.to_mesh()
    t = trimesh.Trimesh(np.asarray(mm.vert_properties)[:, :3], np.asarray(mm.tri_verts), process=True)
    assert t.is_watertight, "mesh is not watertight"
    return t


def preview(T, shell, cover_b, bracket, leg, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection

    screen = box(PX0 + DISP_X0 + NOTCH_D, PX0 + DISP_X0 + DISP_L, -DISP_W / 2, DISP_W / 2, FRONT_T, FRONT_T + 0.2)
    back = [(hinge_turn(leg, LEG_OPEN), "#c8c8c2"), (bracket, "#d4d4cf"), (cover_b, "#e2e2de")]
    front = [(shell, "#eeeeea"), (screen, "#111111")]
    # matplotlib has no depth buffer: parts are drawn back to front according to the view
    views = [(14, -62, "on desk", back + front), (0, 0, "side", front + back), (22, 125, "rear", front[:1] + back[::-1])]
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
    assert (shell ^ cover_b).volume() < 1e-3, "cover hits the body"
    assert (shell.transform(shell_to_base()) ^ base).volume() < 1e-3, "body hits the stand"
    check_hinge(shell, cover_b, bracket, leg)
    lug, knuckle = build_hinge_test(bracket, leg)
    parts = {"test_hinge_eye": printed(lug.translate([0, 0, -D])),
             "test_hinge_knuckle": to_side_print(printed(knuckle)),
             "cover": printed(build_cover()),
             "rail": printed(bracket.translate([0, 0, -D])),
             "leg": to_side_print(printed(leg)),
             "body": printed(shell), "stand": base}
    stl = OUT / "stl"
    stl.mkdir(exist_ok=True)
    meshes = {}
    for name, m in parts.items():
        t = mesh(m)
        t.export(stl / f"{name}.stl")
        meshes[name] = t
        print(f"{name:18s} {t.extents[0]:6.1f} × {t.extents[1]:5.1f} × {t.extents[2]:5.1f} mm  {t.volume / 1000:6.1f} cm³")
    print(f"body total {2 * HL:.1f} × {2 * HH:.1f} × {D:.1f} mm, window {WW:.1f} × {WH:.1f}")
    front, back = stand_margins(T, shell, cover_b, bracket, leg)
    _, _, _, (h0, h1) = heel_contact()
    print(f"on the kickstand: tilt {STAND_TILT:.0f}°, centre of mass {front:.1f} mm behind the bottom edge of the body, {back:.1f} mm in front of the foot; "
          f"heel lands on the rail at y {h0:.1f}–{h1:.1f}, latched leg ends at y {leg.bounding_box()[1]:.1f}")

    desc = (f"Home assistant phone holder (ASUS ZenFone 5Z landscape), version {VERSION}. "
            "Plate 1: hinge test. Join a piece of rail with a lug and a piece of leg with one M2.5x10 screw through the lug into the leg. "
            f"Tighten the screw only so far that the leg still turns; it must stop at {LEG_OPEN:.0f} degrees. "
            "Plate 2: new cover (cable exits at the back through the cut-out at bottom right, sound out through holes at the back near the ends), "
            "rail with hinge and leg. The rail is screwed on with the cover's top two screws, the leg with two "
            "M2.5x10 screws from the sides through the lugs. 6 screws in total. On a wall: unscrew the rail, screw the cover on alone. "
            "Plate 3: new face and black cover. Face 1.2 mm, sound out through 2 mm dots near the ends, whole box 5 mm shorter than the first print. "
            "The v2 cover does not fit the new body, the rail and leg from v2 do. Wall keyhole spacing is "
            f"{2 * KEY_X:.1f} mm.")
    if write_3mf is None:
        print("bambu3mf missing: the 3MF stays as it was, only the STL files in stl/ are new")
    else:
        brim = {"brim_type": "outer_only"}    # the leg stands on its side: 32 mm tall and 2.5 mm thin
        write_3mf(OUT / "holder.3mf", [
            [("Test hinge lug", meshes["test_hinge_eye"], 1), ("Test hinge knuckle", meshes["test_hinge_knuckle"], 1)],
            [("Back cover", meshes["cover"], 1), ("Hinge bracket", meshes["rail"], 1),
             ("Kickstand", meshes["leg"], 1, brim)],
            [("Front frame", meshes["body"], 2), ("Back cover", meshes["cover"], 2)],
        ], plate_names=["1 Hinge test - white PLA", "2 Back cover and kickstand - white PLA",
                        "3 Front frame and back cover - black PLA"],
            info={"Title": f"Home assistant phone holder {VERSION}", "Designer": "Lukas Kmoch", "Description": desc},
            filaments=[("Generic PLA", "#F2F2F2"), ("Generic PLA", "#1A1A1A")])
    preview(T, shell, cover_b, bracket, leg, OUT / "preview.png")


if __name__ == "__main__":
    main()
