package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.mapper.*;
import hainanMahjong.pojo.GameSessions;
import hainanMahjong.vo.recordvo.*;
import hainanMahjong.vo.recordvo.RecordsV2VO.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 可选实库验收：使用连接级临时表，不改持久化表。普通单测无需 MySQL。 */
@EnabledIfSystemProperty(named="records.mysql.url", matches="jdbc:mysql:.*")
class RecordsV2SqlIT {
    private SqlSession sql;
    private RecordsV2Service service;
    private RecordsServiceImpl legacy;

    @BeforeEach void setup() throws Exception {
        UnpooledDataSource data = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                System.getProperty("records.mysql.url"),
                System.getProperty("records.mysql.user", "root"),
                System.getenv().getOrDefault("RECORDS_TEST_MYSQL_PASSWORD", ""));
        Configuration config = new Configuration(new Environment("records-test", new JdbcTransactionFactory(), data));
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        config.addMapper(RecordsV2Mapper.class); config.addMapper(GameRoundsMapper.class);
        sql = new SqlSessionFactoryBuilder().build(config).openSession(false);
        try (Statement stmt = sql.getConnection().createStatement()) {
            stmt.execute("CREATE TEMPORARY TABLE game_sessions (id BIGINT PRIMARY KEY, room_id VARCHAR(20), player_ids JSON, status TINYINT, start_time DATETIME, end_time DATETIME)");
            stmt.execute("CREATE TEMPORARY TABLE game_rounds (id BIGINT PRIMARY KEY, session_id BIGINT, round_num INT, is_draw BOOL, win_type TINYINT, winner_id BIGINT, loser_id BIGINT, win_tile INT, win_hand JSON, fan_info JSON, total_fan INT, dealer VARCHAR(8), wind TINYINT, UNIQUE KEY uk_session_round(session_id, round_num))");
            stmt.execute("CREATE TEMPORARY TABLE game_round_scores (id BIGINT AUTO_INCREMENT PRIMARY KEY, round_id BIGINT, user_id BIGINT, seat TINYINT, score_change INT, is_winner BOOL, detail JSON, UNIQUE KEY uk_round_user(round_id,user_id))");
            stmt.execute("INSERT INTO game_sessions VALUES (42,'123456','[7,-1,-2,-3]',0,NOW(),NULL)");
        }
        GameSessions session = new GameSessions(); session.setId(42L); session.setRoomId("123456"); session.setPlayerIds("[7,-1,-2,-3]");
        GameSessionsMapper sessions = mock(GameSessionsMapper.class);
        when(sessions.selectById(42L)).thenReturn(session);
        when(sessions.selectRoomIdById(42L)).thenReturn("123456");
        when(sessions.selectTotals(anyCollection())).thenAnswer(inv -> {
            List<PlayerTotalVO> out = new ArrayList<>();
            try (Statement stmt = sql.getConnection().createStatement(); ResultSet rs = stmt.executeQuery(
                    "SELECT s.user_id, s.seat, SUM(s.score_change) FROM game_round_scores s GROUP BY s.user_id, s.seat")) {
                while (rs.next()) { PlayerTotalVO total = new PlayerTotalVO(); total.setSessionId(42L); total.setUserId(rs.getLong(1)); total.setSeat(rs.getInt(2)); total.setTotalScore(rs.getInt(3)); out.add(total); }
            }
            return out;
        });
        legacy = new RecordsServiceImpl(sessions, sql.getMapper(GameRoundsMapper.class), mock(UserMapper.class));
        service = new RecordsV2Service(sessions, sql.getMapper(RecordsV2Mapper.class), legacy);
    }

    @AfterEach void close() { if (sql != null) sql.close(); }

    private void append(int from, int through) throws Exception {
        try (PreparedStatement rounds = sql.getConnection().prepareStatement("INSERT INTO game_rounds VALUES (?,42,?,0,0,7,NULL,1,'{\"hand\":[1,2,3],\"melds\":[]}','{\"TEST_FAN\":2}',2,'EAST',0)");
             PreparedStatement scores = sql.getConnection().prepareStatement("INSERT INTO game_round_scores(round_id,user_id,seat,score_change,is_winner,detail) VALUES (?,?,?,?,?,'{\"notes\":[\"分数明细\"],\"flowers\":[],\"gangs\":[]}')")) {
            for (int n=from; n<=through; n++) {
                rounds.setLong(1,n); rounds.setInt(2,n); rounds.addBatch();
                for (int seat=0; seat<4; seat++) {
                    scores.setLong(1,n); scores.setLong(2,seat==0?7:-seat); scores.setInt(3,seat);
                    scores.setInt(4,seat==0?6:-2); scores.setInt(5,seat==0?1:0); scores.addBatch();
                }
            }
            rounds.executeBatch(); scores.executeBatch(); sql.commit(true);
        }
    }

    @ParameterizedTest @ValueSource(ints={10,100,1000})
    void sqlPagesAndDetailsAgreeWithLegacy(int count) throws Exception {
        append(1,count);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        RecordDetailVO old = legacy.selectDetail(42,7L,true);
        Overview overview = service.overview(42,7,null);
        assertEquals(6L*count, overview.getTotalScore()); assertEquals(count,overview.getWinCount());
        assertEquals(old.getOverview().getHighestFan(),overview.getHighestFan());
        int after=0, seen=0;
        while (true) {
            RoundPage page = service.rounds(42,7,after,10,count);
            assertTrue(json.writeValueAsBytes(page).length < 9000);
            for (RoundSummary round : page.getRounds()) {
                assertEquals(++seen,round.getRoundNum()); assertEquals(4,round.getParticipants().size());
                for (Score score : round.getParticipants()) assertEquals(score.getSeat()==0?6:-2,score.getScoreChange());
            }
            after=page.getNextAfterRoundNum(); if (!page.isHasMore()) break;
        }
        assertEquals(count, seen);
        assertEquals(json.writeValueAsString(old.getRounds().get(0)), json.writeValueAsString(service.detail(42,7,1,count).get("round")));
        Map<String,Object> firstList = service.list(7,1,10,null);
        assertEquals(1L, firstList.get("total"));
        assertEquals(6*count, ((RecordsListVO)((List<?>)firstList.get("records")).get(0)).getTotalScore());
        assertTrue(json.writeValueAsBytes(firstList).length < 2000);
        System.out.printf("[V2 SQL] rounds=%d listBytes=%d overviewBytes=%d firstPageBytes=%d legacyBytes=%d%n",
                count, json.writeValueAsBytes(firstList).length, json.writeValueAsBytes(overview).length,
                json.writeValueAsBytes(service.rounds(42,7,0,10,count)).length, json.writeValueAsBytes(old).length);
        try (Statement stmt = sql.getConnection().createStatement()) {
            stmt.execute("INSERT INTO game_sessions VALUES (43,'123457','[7,-1,-2,-3]',0,NOW(),NULL)"); sql.commit(true);
        }
        assertEquals(1L, service.list(7,1,10,42L).get("total"));
        assertEquals(0L, service.list(999,1,10,null).get("total"));
        append(count+1,count+2);
        assertTrue(service.rounds(42,7,count,10,count).getRounds().isEmpty());
        assertEquals(List.of(count+1,count+2),service.rounds(42,7,count,10,null).getRounds().stream().map(RoundSummary::getRoundNum).toList());
        assertEquals(6L*(count+2),service.overview(42,7,null).getTotalScore());
    }
}
