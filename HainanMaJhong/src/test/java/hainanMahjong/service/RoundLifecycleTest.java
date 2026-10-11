package hainanMahjong.service;

import hainanMahjong.mapper.GameRoundsMapper;
import hainanMahjong.mapper.GameSessionsMapper;
import hainanMahjong.pojo.GameSessions;
import hainanMahjong.service.impl.RoundPersister;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoundLifecycleTest {
    @Test void lateSettlementIsRejectedAfterSessionClose() {
        GameSessionsMapper sessions = mock(GameSessionsMapper.class);
        GameRoundsMapper rounds = mock(GameRoundsMapper.class);
        GameSessions closed = new GameSessions(); closed.setStatus(2);
        when(sessions.lockSession(42)).thenReturn(closed);
        RoundPersister persist = new RoundPersister(sessions, rounds);
        assertThrows(IllegalStateException.class, () -> persist.saveHand(42, 1, true, 2,
                null, null, null, null, "{}", 0, "EAST", 0, Collections.emptyList()));
        verifyNoInteractions(rounds);
    }

    @Test void duplicateSettlementDoesNotReplaceCommittedScores() {
        GameSessionsMapper sessions = mock(GameSessionsMapper.class);
        GameRoundsMapper rounds = mock(GameRoundsMapper.class);
        when(sessions.lockSession(42)).thenReturn(new GameSessions());
        when(rounds.findRoundId(42L, 1)).thenReturn(99L);
        RoundPersister persist = new RoundPersister(sessions, rounds);
        assertEquals(99, persist.saveHand(42, 1, true, 2,
                null, null, null, null, "{}", 0, "EAST", 0, Collections.emptyList()));
        verify(rounds, never()).deleteScoresByRound(99L);
        verify(sessions, never()).updateCurrentRound(42L, 1);
    }
}
