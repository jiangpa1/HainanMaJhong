package hainanMahjong.engine;

import hainanMahjong.engine.bot.BotController;
import hainanMahjong.engine.model.*;
import hainanMahjong.engine.port.*;
import hainanMahjong.rules.HuResult;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Traces were generated from the pre-migration working-tree backup, including its claim fix. */
class EngineBehaviorParityTest {
    private static final class Trace implements GameListener {
        final StringBuilder text = new StringBuilder();
        void add(Object... values) { text.append(Arrays.deepToString(values)).append('\n'); }
        public void onShuffle(int n) { add("shuffle", n); }
        public void onDeal(Seat s) { add("deal", s); }
        public void onDraw(Seat s, int t) { add("draw", s, t); }
        public void onFlower(Seat s, int t) { add("flower", s, t); }
        public void onDiscard(Seat s, int t) { add("discard", s, t); }
        public void onMeld(Seat s, Meld m) { add("meld", s, m.type(), m.tiles(), m.from()); }
        public void onHu(Seat s, HuResult r, boolean sd, boolean th, int bt, int t, Seat from) {
            add("hu", s, sd, th, bt, t, from);
        }
        public void onTimeout(Seat s, String a) { fail("Synchronous bots must not time out"); }
        public void onRoundDraw() { add("drawn"); }
        public void onResume() { add("resume"); }
        public void onTurnStart(Seat s, String kind, long ms) { add("turn", s, kind, ms); }
        public void onReport(Seat s, int mode) { add("report", s, mode); }
        public void onEnd(RoundResult r) {
            add("end", r.isDraw, r.winner, r.selfDraw, r.ganKai, r.qiangGang,
                    r.tianHu, r.winnerBaoTing, r.winTile, r.from, r.winHand);
        }
        String hash() throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test void deterministicHandsMatchPreMigrationTraces() throws Exception {
        List<String> actual = new ArrayList<>();
        for (int h = 0; h < 400; h++) {
            long seed = 2026L + h;
            Map<Seat, PlayerController> controllers = new EnumMap<>(Seat.class);
            for (Seat s : Seat.values()) controllers.put(s, new BotController(seed + s.ordinal() * 7919L));
            GameConfig cfg = new GameConfig(true, Seat.values()[h % 4], 2000, 1500,
                    seed, true, 0, true, h % 4);
            Trace trace = new Trace();
            new RoomManager(cfg, controllers, trace).start();
            actual.add(seed + " " + trace.hash());
        }
        try (var stream = getClass().getResourceAsStream("/engine-traces-before-event-driven.txt")) {
            assertNotNull(stream);
            List<String> expected = new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertEquals(expected, actual, "Gameplay events must match the original engine");
        }
    }
}
