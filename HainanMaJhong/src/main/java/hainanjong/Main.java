package hainanjong;

import java.util.Random;

/**
 * 演示与自检：示例用例 + 随机对照（查表法 vs 独立暴力递归）。
 */
public class Main {

    public static void main(String[] args) {
        int[] sizes = HuLib.tableSizes();
        System.out.println("查表规模: 花色无将=" + sizes[0] + ", 花色含将=" + sizes[1]
                + ", 字无将=" + sizes[2] + ", 字含将=" + sizes[3]);
        System.out.println();

        demo();

        System.out.println();
        int err = fuzz(200000);
        System.out.println(err == 0
                ? "随机对照 200000 手：全部一致，通过。"
                : "随机对照发现 " + err + " 处不一致！");
    }

    // ---- 构造辅助：成对 (下标, 张数) ----
    static int[] build(int[]... pairs) {
        int[] t = new int[HuLib.TOTAL_TILES];
        for (int[] p : pairs) {
            t[p[0]] = p[1];
        }
        return t;
    }

    static void demo() {
        Object[][] cases = {
            {"清一色", build(
                new int[]{0, 3}, new int[]{1, 1}, new int[]{2, 1}, new int[]{3, 1},
                new int[]{4, 1}, new int[]{5, 1}, new int[]{6, 1}, new int[]{7, 2}, new int[]{8, 3}),
                "清一色"},
            {"混一色", build(
                new int[]{0, 1}, new int[]{1, 1}, new int[]{2, 1}, new int[]{3, 1}, new int[]{4, 1},
                new int[]{5, 1}, new int[]{6, 1}, new int[]{7, 1}, new int[]{8, 1},
                new int[]{27, 3}, new int[]{31, 2}),
                "混一色"},
            {"碰碰胡", build(new int[]{0, 3}, new int[]{17, 3}, new int[]{18, 3}, new int[]{27, 3}, new int[]{31, 2}),
                "碰碰胡"},
            {"清一色+碰碰胡", build(new int[]{0, 3}, new int[]{1, 3}, new int[]{2, 3}, new int[]{3, 3}, new int[]{4, 2}),
                "碰碰胡,清一色"},
            {"七对", build(new int[]{0, 2}, new int[]{8, 2}, new int[]{9, 2}, new int[]{17, 2}, new int[]{18, 2}, new int[]{26, 2}, new int[]{31, 2}),
                "七对"},
            {"清一色七对", build(new int[]{0, 2}, new int[]{1, 2}, new int[]{2, 2}, new int[]{3, 2}, new int[]{4, 2}, new int[]{5, 2}, new int[]{6, 2}),
                "七对,清一色"},
            {"十三幺", build(new int[]{0, 1}, new int[]{8, 1}, new int[]{9, 1}, new int[]{17, 1}, new int[]{18, 1}, new int[]{26, 1},
                new int[]{27, 1}, new int[]{28, 1}, new int[]{29, 1}, new int[]{30, 1}, new int[]{31, 2}, new int[]{32, 1}, new int[]{33, 1}),
                "十三幺"},
            {"不胡", build(new int[]{0, 1}, new int[]{1, 1}, new int[]{2, 1}, new int[]{3, 1}, new int[]{4, 1},
                new int[]{5, 1}, new int[]{6, 1}, new int[]{7, 1}, new int[]{8, 1},
                new int[]{9, 1}, new int[]{10, 1}, new int[]{11, 1}, new int[]{12, 1}, new int[]{31, 1}),
                "无"},
            {"碰碰胡+花牌", build(new int[]{0, 3}, new int[]{17, 3}, new int[]{18, 3}, new int[]{27, 3}, new int[]{31, 2},
                new int[]{34, 2}, new int[]{35, 1}),
                "碰碰胡(花3)"},
        };

        int fail = 0;
        for (Object[] c : cases) {
            String name = (String) c[0];
            int[] tiles = (int[]) c[1];
            String expect = (String) c[2];
            String actual = describe(HuLib.checkHu(tiles));
            boolean ok = expect.equals(actual);
            if (!ok) {
                fail++;
            }
            System.out.printf("[%s] %-14s => %-16s 期望:%-16s%s%n",
                    ok ? "OK" : "FAIL", name, actual, expect,
                    ok ? "" : "  手牌=" + HuLib.formatHand(tiles));
        }
        System.out.println(fail == 0 ? "示例全部通过。" : "示例失败 " + fail + " 个。");
    }

    static String describe(HuResult r) {
        if (!r.isHu) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.fanTypes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(r.fanTypes.get(i));
        }
        if (r.flowerCount > 0) {
            sb.append("(花").append(r.flowerCount).append(")");
        }
        return sb.toString();
    }

    // ==================== 随机对照（查表法 vs 暴力递归） ====================

    static final int[] ORPHANS = {0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33};

    static int fuzz(int n) {
        Random rnd = new Random(12345);
        int err = 0;
        for (int k = 0; k < n; k++) {
            int mode = rnd.nextInt(10);
            int[] t34;
            if (mode < 5) {
                t34 = randomStandard(rnd);
            } else if (mode < 8) {
                t34 = randomArbitrary(rnd);
            } else if (mode == 8) {
                t34 = randomSevenPairs(rnd);
            } else {
                t34 = randomOrphans(rnd);
            }

            boolean lib = HuLib.checkHu(to42(t34)).isHu;
            boolean ref = refIsHu(t34);
            if (lib != ref) {
                err++;
                if (err <= 10) {
                    System.out.println("不一致: 查表=" + lib + " 暴力=" + ref + "  " + HuLib.formatHand(to42(t34)));
                }
            }
        }
        return err;
    }

    static int[] to42(int[] t34) {
        int[] t = new int[42];
        System.arraycopy(t34, 0, t, 0, 34);
        return t;
    }

    static int[] randomStandard(Random r) {
        int[] t = new int[34];
        for (int m = 0; m < 4; m++) {
            if (r.nextBoolean()) {
                int i;
                do {
                    i = r.nextInt(34);
                } while (t[i] + 3 > 4);
                t[i] += 3;
            } else {
                int suit, start;
                do {
                    suit = r.nextInt(3);
                    start = suit * 9 + r.nextInt(7);
                } while (t[start] + 1 > 4 || t[start + 1] + 1 > 4 || t[start + 2] + 1 > 4);
                t[start]++;
                t[start + 1]++;
                t[start + 2]++;
            }
        }
        int i;
        do {
            i = r.nextInt(34);
        } while (t[i] + 2 > 4);
        t[i] += 2;
        return t;
    }

    static int[] randomArbitrary(Random r) {
        int[] t = new int[34];
        for (int k = 0; k < 14; k++) {
            int i;
            do {
                i = r.nextInt(34);
            } while (t[i] >= 4);
            t[i]++;
        }
        return t;
    }

    static int[] randomSevenPairs(Random r) {
        int[] t = new int[34];
        for (int p = 0; p < 7; p++) {
            int i;
            do {
                i = r.nextInt(34);
            } while (t[i] + 2 > 4);
            t[i] += 2;
        }
        return t;
    }

    static int[] randomOrphans(Random r) {
        int[] t = new int[34];
        for (int o : ORPHANS) {
            t[o] = 1;
        }
        t[ORPHANS[r.nextInt(ORPHANS.length)]]++;
        return t;
    }

    // ---- 参考实现（独立暴力，与查表法对照） ----

    static boolean refIsHu(int[] t) {
        int total = 0;
        boolean even = true;
        for (int i = 0; i < 34; i++) {
            total += t[i];
            if (t[i] % 2 != 0) {
                even = false;
            }
        }
        if (total != 14) {
            return false;
        }
        if (even) {
            return true;                 // 七对
        }
        if (refOrphans(t)) {
            return true;                 // 十三幺
        }
        return bruteStandard(t.clone()); // 普通
    }

    static boolean refOrphans(int[] t) {
        for (int i = 0; i < 34; i++) {
            if (t[i] > 0 && !isOrphanRef(i)) {
                return false;
            }
        }
        for (int o : ORPHANS) {
            if (t[o] == 0) {
                return false;
            }
        }
        return true;
    }

    static boolean isOrphanRef(int i) {
        if (i >= 27) {
            return true;
        }
        int m = i % 9;
        return m == 0 || m == 8;
    }

    static boolean bruteStandard(int[] t) {
        return canForm(t, true);
    }

    static boolean canForm(int[] t, boolean needPair) {
        int first = -1;
        for (int i = 0; i < 34; i++) {
            if (t[i] > 0) {
                first = i;
                break;
            }
        }
        if (first == -1) {
            return !needPair;
        }

        int c = t[first];
        if (needPair && c >= 2) {
            t[first] -= 2;
            if (canForm(t, false)) {
                t[first] += 2;
                return true;
            }
            t[first] += 2;
        }
        if (c >= 3) {
            t[first] -= 3;
            if (canForm(t, needPair)) {
                t[first] += 3;
                return true;
            }
            t[first] += 3;
        }
        if (first < 27 && first % 9 <= 6 && t[first + 1] >= 1 && t[first + 2] >= 1) {
            t[first]--;
            t[first + 1]--;
            t[first + 2]--;
            if (canForm(t, needPair)) {
                t[first]++;
                t[first + 1]++;
                t[first + 2]++;
                return true;
            }
            t[first]++;
            t[first + 1]++;
            t[first + 2]++;
        }
        return false;
    }
}
