package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.exception.BusinessException;
import hainanMahjong.mapper.*;
import hainanMahjong.pojo.GameSessions;
import hainanMahjong.vo.recordvo.*;
import hainanMahjong.vo.recordvo.RecordsV2VO.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecordsV2ServiceTest {
    private static final long SID = 42, USER = 7;
    private final GameSessionsMapper sessions = mock(GameSessionsMapper.class);
    private final GameRoundsMapper rounds = mock(GameRoundsMapper.class);
    private final RecordsV2Mapper queries = mock(RecordsV2Mapper.class);
    private final RecordsServiceImpl legacy = new RecordsServiceImpl(sessions, rounds, mock(UserMapper.class));
    private final RecordsV2Service service = new RecordsV2Service(sessions, queries, legacy);
    private final List<RoundPlayerRowVO> history = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    private void fixture(int count) {
        GameSessions session = new GameSessions();
        session.setId(SID); session.setRoomId("123456"); session.setPlayerIds("[7,-1,-2,-3]");
        when(sessions.selectById(SID)).thenReturn(session);
        when(sessions.selectRoomIdById(SID)).thenReturn("123456");
        when(queries.latestRound(SID)).thenAnswer(inv -> history.size() / 4);
        when(queries.highestFan(eq(SID), anyInt(), anyInt(), eq(USER))).thenReturn("{\"TEST_FAN\":2}");
        when(rounds.selectRoundsAll(SID)).thenAnswer(inv -> history);
        when(sessions.selectTotals(anyCollection())).thenAnswer(inv -> {
            List<PlayerTotalVO> out = new ArrayList<>();
            for (int seat=0; seat<4; seat++) {
                PlayerTotalVO total = new PlayerTotalVO(); total.setSessionId(SID); total.setUserId(user(seat)); total.setSeat(seat);
                total.setTotalScore((history.size()/4)*(seat==0?6:-2)); out.add(total);
            }
            return out;
        });
        when(queries.stats(eq(SID), anyInt(), anyInt())).thenAnswer(inv -> {
            int after = inv.getArgument(1), cutoff = inv.getArgument(2);
            List<Stats> out = new ArrayList<>();
            for (int seat=0; seat<4; seat++) {
                Stats stats = new Stats(); stats.setUserId(user(seat)); stats.setSeat(seat);
                stats.setTotalScore((cutoff-after)*(seat==0?6:-2));
                stats.setWinCount(seat==0?cutoff-after:0); stats.setHighestFanValue(seat==0?2:0); out.add(stats);
            }
            return out;
        });
        when(queries.keys(eq(SID), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int after = inv.getArgument(1), cutoff = inv.getArgument(2), limit = inv.getArgument(3);
            List<RoundKey> keys = new ArrayList<>();
            for (int n=after+1; n<=cutoff && keys.size()<limit; n++) {
                RoundKey key = new RoundKey(); key.setId(n); key.setRoundNum(n); keys.add(key);
            }
            return keys;
        });
        when(queries.summaries(eq(SID), anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(1);
            return ids.stream().map(id -> {
                RoundSummary summary = new RoundSummary(); summary.setRoundId(id); summary.setRoundNum(id.intValue());
                summary.setWinnerId(USER); summary.setWinType(0); summary.setTotalFan(2); return summary;
            }).toList();
        });
        when(queries.scores(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0); List<Score> scores = new ArrayList<>();
            for (long id : ids) for (int seat=0; seat<4; seat++) {
                Score score = new Score(); score.setRoundId(id); score.setUserId(user(seat)); score.setSeat(seat);
                score.setScoreChange(seat==0?6:-2); score.setWinner(seat==0); scores.add(score);
            }
            return scores;
        });
        when(queries.detail(eq(SID), anyInt(), anyInt())).thenAnswer(inv -> {
            int n = inv.getArgument(1); return history.stream().filter(row -> row.getRoundNum()==n).toList();
        });
        append(count);
    }

    private long user(int seat) { return seat==0?USER:-seat; }
    private void append(int count) {
        int first = history.size()/4+1;
        for (int n=first; n<first+count; n++) for (int seat=0; seat<4; seat++) {
            RoundPlayerRowVO row = new RoundPlayerRowVO();
            row.setRoundNum(n); row.setUserId(user(seat)); row.setSeat(seat); row.setWinner(seat==0);
            row.setWinnerId(USER); row.setScoreChange(seat==0?6:-2); row.setTotalFan(2); row.setWinType(0);
            row.setFanInfo("{\"TEST_FAN\":2}"); row.setWinHand("{\"hand\":[1,2,3],\"melds\":[]}");
            row.setDetail("{\"notes\":[\"" + "大字段".repeat(150) + "\"],\"flowers\":[],\"gangs\":[]}"); history.add(row);
        }
    }

    @ParameterizedTest @ValueSource(ints={10,100,1000})
    void boundedPagesHaveFourPlayersAndMatchLegacyScores(int count) throws Exception {
        fixture(count);
        RecordDetailVO old = legacy.selectDetail(SID, USER, true);
        Overview overview = service.overview(SID, USER, null);
        assertEquals(old.getOverview().getWinCount(), overview.getWinCount());
        assertEquals(old.getOverview().getHighestFan(), overview.getHighestFan());
        for (PlayerTotalVO player : old.getOverview().getPlayers())
            assertEquals(player.getTotalScore(), overview.getPlayers().stream().filter(p -> p.getUserId().equals(player.getUserId())).findFirst().orElseThrow().getTotalScore());
        Set<Integer> seen = new HashSet<>(); int after=0;
        do {
            RoundPage page = service.rounds(SID, USER, after, 10, count);
            assertTrue(json.writeValueAsBytes(page).length < 9000, "响应大小必须与总历史长度无关");
            assertFalse(json.writeValueAsString(page).contains("大字段"));
            assertFalse(json.writeValueAsString(page).contains("winHand"));
            for (RoundSummary round : page.getRounds()) {
                assertTrue(seen.add(round.getRoundNum())); assertEquals(4, round.getParticipants().size());
                RecordRoundVO full = old.getRounds().get(round.getRoundNum()-1);
                for (Score score : round.getParticipants())
                    assertEquals(full.getParticipants().stream().filter(p -> p.getUserId().equals(score.getUserId())).findFirst().orElseThrow().getScoreChange(), score.getScoreChange());
            }
            after = page.getNextAfterRoundNum();
            if (!page.isHasMore()) break;
        } while(true);
        assertEquals(count, seen.size());
        assertTrue(json.writeValueAsBytes(overview).length < 2000);
        Map<String,Object> detail = service.detail(SID, USER, 1, count);
        assertEquals(json.writeValueAsString(old.getRounds().get(0)), json.writeValueAsString(detail.get("round")));
    }

    @Test void cutoffSurvivesConcurrentSettlementAndIncrementalStatsOnlyReadNewRounds() {
        fixture(10);
        Overview first = service.overview(SID, USER, null);
        service.overview(SID, USER, 10);
        verify(queries, times(1)).stats(SID, 0, 10);
        verify(queries, times(1)).highestFan(SID, 0, 10, USER);
        append(2);
        RoundPage frozen = service.rounds(SID, USER, 5, 10, first.getReadThroughRoundNum());
        assertEquals(List.of(6,7,8,9,10), frozen.getRounds().stream().map(RoundSummary::getRoundNum).toList());
        RoundPage added = service.rounds(SID, USER, 10, 10, null);
        assertEquals(List.of(11,12), added.getRounds().stream().map(RoundSummary::getRoundNum).toList());
        Overview current = service.overview(SID, USER, added.getReadThroughRoundNum());
        assertEquals(72, current.getTotalScore()); assertEquals(12, current.getWinCount());
        verify(queries).stats(SID, 10, 12);
        assertEquals(60, service.overview(SID, USER, 10).getTotalScore());
        verify(queries, times(2)).stats(SID, 0, 10);
    }

    @Test void authorizationPrecedesCacheAndAllRoundQueries() {
        fixture(10); service.overview(SID, USER, null); clearInvocations(queries);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.overview(SID, 999, null)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.rounds(SID, 999, 0, 10, null)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.detail(SID, 999, 1, null)).getCode());
        verifyNoInteractions(queries);
        clearInvocations(rounds);
        assertEquals(403, assertThrows(BusinessException.class, () -> legacy.selectDetail(SID, 999L, true)).getCode());
        verifyNoInteractions(rounds);
    }

    @Test void hardLimitsAndBadCursorsAreRejected() {
        fixture(10);
        for (int size : new int[]{0,-1,51,Integer.MAX_VALUE})
            assertEquals(400, assertThrows(BusinessException.class, () -> service.rounds(SID, USER, 0, size, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.rounds(SID, USER, -1, 10, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.rounds(SID, USER, 11, 10, 10)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.overview(SID, USER, 11)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(SID, USER, 11, 10)).getCode());
        assertThrows(BusinessException.class, () -> service.list(USER, 1, 21, null));
    }

    @Test void incompleteRoundFailsInsteadOfReturningSplitPlayers() {
        fixture(10);
        when(queries.scores(anyList())).thenReturn(List.of(new Score()));
        assertEquals(500, assertThrows(BusinessException.class, () -> service.rounds(SID, USER, 0, 10, null)).getCode());
    }

    @Test void emptySessionHasFourZeroTotalsAndDoesNotQueryHeavyFields() {
        fixture(0);
        Overview overview = service.overview(SID, USER, null);
        assertEquals(4, overview.getPlayers().size()); assertEquals(0, overview.getTotalScore()); assertEquals("无", overview.getHighestFan());
        assertTrue(service.rounds(SID, USER, 0, 10, null).getRounds().isEmpty());
        verify(queries, never()).stats(anyLong(), anyInt(), anyInt());
        verify(queries, never()).scores(anyList()); verify(queries, never()).summaries(anyLong(), anyList());
    }

    @Test void sessionListUsesOneBatchTotalQueryWithoutLoadingIndividualOverviews() {
        fixture(10);
        RecordsListVO row = new RecordsListVO(); row.setSessionId(SID); row.setRoomId("123456"); row.setPlayerIds("[7,-1,-2,-3]");
        when(queries.latestSession(USER)).thenReturn(SID);
        when(queries.sessionCount(USER,SID)).thenReturn(1L);
        when(queries.sessions(USER,SID,10,0)).thenReturn(List.of(row));
        Map<String,Object> list = service.list(USER,1,10,null);
        assertEquals(SID,list.get("readThroughSessionId")); assertEquals(60,row.getTotalScore()); assertEquals(1,row.getRank());
        assertEquals(4,row.getPlayers().size());
        verify(sessions,times(1)).selectTotals(List.of(SID));
        verify(sessions,never()).selectById(anyLong());
        verify(queries,never()).latestRound(anyLong());
        verify(queries,never()).stats(anyLong(),anyInt(),anyInt());
        verify(queries,never()).highestFan(anyLong(),anyInt(),anyInt(),anyLong());
    }
}
