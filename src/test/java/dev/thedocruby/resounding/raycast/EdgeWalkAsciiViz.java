package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/**
 * ASCII visualizer for the four forward-orthant maps. Classification and bounce-origin walk come
 * from {@link FrustumLod} (same code {@link Cast} uses). Outbound path ink after the bounce is
 * drawn from that interaction (reflect / pass / both).
 *
 * <p>Run from {@code repo/}:
 * <pre>
 *   nix develop -c ./gradlew -q edgeWalkViz
 * </pre>
 */
public final class EdgeWalkAsciiViz {

	private static final int Q = 16;
	private static final int FRAME_H = 2 + Q + 1 + Q + 2;
	private static final int TAIL = 3;
	private static final int SIDE = 4;
	private static final int PANEL_W = 2 + Q + 1 + Q + 2 + SIDE;
	private static final int PANEL_H = FRAME_H + TAIL;

	/** Unit cell H on XY; N = +Y face; E = +X; ray heading +X+Y. Matches Cast's open-cell exit. */
	private static final Vec3d CELL_BASE = Vec3d.ZERO;
	private static final int CELL_SIZE = 1;
	private static final Vec3i FACE_N = new Vec3i(0, 1, 0);
	private static final Vec3d RAY = new Vec3d(1, 1, 0);

	private enum Cell {
		H("H"), N("N"), E("E"), D("D");
		final String label;
		Cell(String label) { this.label = label; }
	}

	private record MapCase(String title, boolean n, boolean e, boolean d, FrustumLod.Interaction want) {}

	private static final MapCase[] CASES = {
			new MapCase("1 CORNER", true, true, true, FrustumLod.Interaction.CORNER),
			new MapCase("2 GAP", true, false, false, FrustumLod.Interaction.GAP),
			new MapCase("3 SPLIT", true, true, false, FrustumLod.Interaction.SPLIT),
			new MapCase("4 FACE", true, false, true, FrustumLod.Interaction.FACE),
	};

	public static void main(String[] args) {
		StringBuilder out = new StringBuilder(32_000);
		out.append("""
				===============================================================================
				 Resounding — forward-orthant maps (FrustumLod.classify + edgeWalk)
				 Plane: XY  |  N = +Y  |  E = +X  |  same maps on XZ / YZ
				 Cast surveys N/E/D, then uses these Interaction values at open-cell exits.
				===============================================================================

				LEGEND
				  X / #   stiff     O / .   open (H is always air)
				  *       face hit
				  =       edgeWalk toward bounce origin (FrustumLod.edgeWalk)
				  / \\     incident / reflected body
				  ~       GAP/SPLIT pass child through E/D
				  v       bounce origin / exit tip

				""");

		String[][] panels = new String[4][];
		for (int i = 0; i < 4; i++) {
			panels[i] = renderPanel(CASES[i]).split("\n", -1);
		}
		int rows = panels[0].length;
		for (int row = 0; row < rows; row++) {
			out.append(pad(panels[0][row], PANEL_W + 2)).append("  ").append(panels[1][row]).append('\n');
		}
		out.append('\n');
		for (int row = 0; row < rows; row++) {
			out.append(pad(panels[2][row], PANEL_W + 2)).append("  ").append(panels[3][row]).append('\n');
		}

		out.append("""

				===============================================================================
				 Shared with Cast: FrustumLod.classify(N,E,D) and FrustumLod.edgeWalk(..., ix).
				 CORNER/SPLIT: walk origin to leading vertex, then face-reflect (into E).
				 FACE: walk along the N wall to the D–E-only vertex, then face-reflect.
				 GAP: no reflect — transmit through E/D.
				 SPLIT: both CORNER reflect child and GAP transmit child.
				===============================================================================
				""");
		System.out.print(out);

		for (MapCase c : CASES) {
			FrustumLod.Interaction got = FrustumLod.classify(c.n, c.e, c.d);
			if (got != c.want) {
				throw new IllegalStateException(c.title + " FrustumLod.classify → " + got);
			}
		}
	}

	private static String renderPanel(MapCase c) {
		int cx = 1 + Q;
		int cy = 1 + Q;
		int y1 = cy + Q;
		char[][] g = blankGrid(PANEL_W, PANEL_H);

		fillQuad(g, cx - Q, cy - Q, Q, Q, Cell.N, c.n);
		fillQuad(g, cx + 1, cy - Q, Q, Q, Cell.D, c.d);
		fillQuad(g, cx - Q, cy + 1, Q, Q, Cell.H, false);
		fillQuad(g, cx + 1, cy + 1, Q, Q, Cell.E, c.e);
		drawFrame(g, cx, cy);

		int hitCol = clamp(cx - 6, cx - Q + 3, cx - 3);
		int hitRow = clamp(cy + 6, cy + 3, cy + Q - 3);
		drawRay(g, hitCol, hitRow);

		FrustumLod.Interaction ix = FrustumLod.classify(c.n, c.e, c.d);
		Vec3d hitWorld = screenToWorld(hitCol, hitRow, cx, cy);
		Vec3d walked = FrustumLod.edgeWalk(hitWorld, CELL_BASE, CELL_SIZE, FACE_N, RAY, 1.0, ix);
		int walkCol = worldToCol(walked.x, cx);
		int walkRow = worldToRow(walked.y, cy);

		if (ix == FrustumLod.Interaction.CORNER || ix == FrustumLod.Interaction.SPLIT) {
			drawCornerReflect(g, hitCol, hitRow, walkCol, walkRow, cx, cy, y1);
		}
		if (ix == FrustumLod.Interaction.GAP || ix == FrustumLod.Interaction.SPLIT) {
			drawPass(g, hitCol, hitRow, cx, cy);
		}
		if (ix == FrustumLod.Interaction.FACE) {
			drawFaceReflect(g, hitCol, hitRow, walkCol, walkRow, cx, cy);
		}

		plot(g, hitCol, hitRow, '*');
		stamp(g, cx + Q - 7, 0, "+E(X)->");
		stamp(g, 0, 0, "+N(Y)");
		stamp(g, 0, 1, "  ^");

		String map = (c.n ? "X" : "O") + (c.d ? "X" : "O") + "/" + "O" + (c.e ? "X" : "O");
		String walkNote = String.format(
				"edgeWalk → (%.2f,%.2f)", walked.x, walked.y);
		StringBuilder sb = new StringBuilder();
		sb.append(pad("--- " + c.title + " ---", PANEL_W)).append('\n');
		sb.append(pad("map " + map + "  →  " + ix, PANEL_W)).append('\n');
		sb.append(pad(actionLine(ix) + "  " + walkNote, PANEL_W)).append('\n');
		for (int r = 0; r < PANEL_H; r++) {
			sb.append(new String(g[r])).append('\n');
		}
		return sb.toString();
	}

	private static String actionLine(FrustumLod.Interaction ix) {
		return switch (ix) {
			case CORNER -> "reflect + walk to vertex";
			case GAP -> "pass through E/D";
			case SPLIT -> "both: vertex walk AND pass";
			case FACE -> "walk to D–E vertex, then reflect";
		};
	}

	/** H is [0,1]×[0,1]; screen +Y is up-page = world +Y toward N. */
	private static Vec3d screenToWorld(int col, int row, int cx, int cy) {
		double x = (col - (cx - Q)) / (double) Q;
		double y = 1.0 - (row - cy) / (double) Q;
		return new Vec3d(x, y, 0);
	}

	private static int worldToCol(double x, int cx) {
		return (cx - Q) + (int) Math.round(x * Q);
	}

	private static int worldToRow(double y, int cy) {
		return cy + (int) Math.round((1.0 - y) * Q);
	}

	private static void fillQuad(char[][] g, int x0, int y0, int w, int h, Cell cell, boolean stiff) {
		char fill = stiff ? '#' : '.';
		char ink = stiff ? 'X' : 'O';
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				plot(g, x, y, fill);
			}
		}
		plot(g, x0 + 1, y0 + 1, ink);
		stamp(g, x0 + 3, y0 + 1, cell.label);
	}

	private static void drawFrame(char[][] g, int cx, int cy) {
		int x0 = cx - Q;
		int y0 = cy - Q;
		int x1 = cx + Q;
		int yBot = cy + Q;
		for (int x = x0; x <= x1; x++) {
			plot(g, x, y0, '-');
			plot(g, x, yBot, '-');
			plot(g, x, cy, '-');
		}
		for (int y = y0; y <= yBot; y++) {
			plot(g, x0, y, '|');
			plot(g, x1, y, '|');
			plot(g, cx, y, '|');
		}
		plot(g, x0, y0, '+');
		plot(g, x1, y0, '+');
		plot(g, x0, yBot, '+');
		plot(g, x1, yBot, '+');
		plot(g, cx, y0, '+');
		plot(g, cx, yBot, '+');
		plot(g, x0, cy, '+');
		plot(g, x1, cy, '+');
		plot(g, cx, cy, '+');
	}

	private static void drawRay(char[][] g, int hitCol, int hitRow) {
		int r = hitRow;
		int c = hitCol;
		for (int i = 0; i < 8; i++) {
			r += 1;
			c -= 1;
			if (!inBounds(g, c, r)) break;
			char e = g[r][c];
			if (e == '+' || e == '|' || e == '-') break;
			plot(g, c, r, '/');
		}
	}

	/** Walk origin to FrustumLod.edgeWalk target, then face-reflect outbound through E. */
	private static void drawCornerReflect(
			char[][] g, int hitCol, int hitRow, int walkCol, int walkRow, int cx, int cy, int y1
	) {
		drawWalk(g, hitCol, hitRow, walkCol, walkRow, '=');
		plot(g, walkCol, walkRow, 'v');
		for (int y = Math.max(walkRow, cy) + 1; y < y1; y++) {
			softPlot(g, cx - 1, y, '=');
			softPlot(g, cx + 1, y, '=');
		}
		plot(g, cx, y1, 'v');
		if (y1 + 1 < PANEL_H) stamp(g, cx + 1, y1 + 1, "/");
		if (y1 + 2 < PANEL_H) stamp(g, cx - 1, y1 + 2, "...");
	}

	private static void drawWalk(char[][] g, int x0, int y0, int x1, int y1, char ink) {
		int x = x0;
		int y = y0;
		for (int guard = 0; guard < 64 && (x != x1 || y != y1); guard++) {
			int sx = Integer.compare(x1, x);
			int sy = Integer.compare(y1, y);
			if (sx != 0) x += sx;
			if (sy != 0) y += sy;
			if (!inBounds(g, x, y)) break;
			if (g[y][x] != '*' && g[y][x] != 'v') softPlot(g, x, y, ink);
		}
	}

	private static void drawPass(char[][] g, int hitCol, int hitRow, int cx, int cy) {
		int x = hitCol;
		int y = hitRow;
		int x1 = cx + Q - 2;
		int y1 = cy - Q + 2;
		for (int guard = 0; guard < 80 && (x != x1 || y != y1); guard++) {
			int sx = Integer.compare(x1, x);
			int sy = Integer.compare(y1, y);
			if (sx != 0) x += sx;
			if (sy != 0) y += sy;
			if (!inBounds(g, x, y)) break;
			if (x <= cx && y >= cy) continue;
			char e = g[y][x];
			if (e == '+' || e == '|' || e == '-') continue;
			if (e != '*' && e != 'v' && e != '=') plot(g, x, y, '~');
		}
	}

	/**
	 * FACE: edgeWalk carries the origin along the N wall to the D–E-only vertex (past H), then
	 * Cast face-reflects so the outbound ray peels into E.
	 */
	private static void drawFaceReflect(
			char[][] g, int hitCol, int hitRow, int walkCol, int walkRow, int cx, int cy
	) {
		drawWalk(g, hitCol, hitRow, walkCol, walkRow, '=');
		// Fill the wall slide with ~ once past the H|E cross so the path reads as along N/D.
		int mid = cx;
		if (walkCol > mid) {
			for (int x = mid; x < walkCol; x++) {
				if (g[cy][x] == '=' || g[cy][x] == '-' || g[cy][x] == '+') {
					plot(g, x, cy, '~');
				}
			}
			// Restore walk ink on the approach inside H.
			drawWalk(g, hitCol, hitRow, Math.min(walkCol, mid), cy, '=');
		}
		plot(g, walkCol, walkRow, 'v');
		int ex = walkCol;
		int ey = walkRow;
		for (int i = 0; i < Q + SIDE; i++) {
			ex += 1;
			ey += 1;
			if (!inBounds(g, ex, ey)) break;
			plot(g, ex, ey, '\\');
		}
	}

	private static void softPlot(char[][] g, int x, int y, char c) {
		if (!inBounds(g, x, y)) return;
		char e = g[y][x];
		if (e == '*' || e == 'v') return;
		if (e >= 'A' && e <= 'Z') return;
		if (e >= 'a' && e <= 'z') return;
		g[y][x] = c;
	}

	private static char[][] blankGrid(int w, int h) {
		char[][] g = new char[h][w];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) g[y][x] = ' ';
		}
		return g;
	}

	private static void plot(char[][] g, int x, int y, char c) {
		if (inBounds(g, x, y)) g[y][x] = c;
	}

	private static void stamp(char[][] g, int x, int y, String s) {
		for (int i = 0; i < s.length(); i++) plot(g, x + i, y, s.charAt(i));
	}

	private static boolean inBounds(char[][] g, int x, int y) {
		return y >= 0 && y < g.length && x >= 0 && x < g[y].length;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static String pad(String s, int w) {
		if (s.length() >= w) return s;
		return s + " ".repeat(w - s.length());
	}
}
