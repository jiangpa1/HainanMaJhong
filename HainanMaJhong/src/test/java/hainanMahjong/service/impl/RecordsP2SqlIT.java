package hainanMahjong.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import hainanMahjong.mapper.*;
import hainanMahjong.ops.RecordsP2Migration;
import hainanMahjong.pojo.GameSessions;
import hainanMahjong.vo.recordvo.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only a dedicated records_p2_test database; never run against business data. */
@EnabledIfSystemProperty(named="records.p2.mysql.url",matches="jdbc:mysql:.*")
class RecordsP2SqlIT {
    private final List<String> queryIds=new java.util.concurrent.CopyOnWriteArrayList<>();
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,
            method="query",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}))
    private class QueryCounter implements org.apache.ibatis.plugin.Interceptor {
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            queryIds.add(((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId());
            return invocation.proceed();
        }
    }
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private RoundPersister persist;
    private RecordProjection projection;
    private RecordsV2Service service;
    private GameSessionsMapper sessions;
    private RecordsP2Migration migration;
    private long sid;

    @BeforeEach void setup() throws Exception {
        source=new DriverManagerDataSource(System.getProperty("records.p2.mysql.url"),System.getProperty("records.p2.mysql.user","root"),
                System.getenv().getOrDefault("RECORDS_TEST_MYSQL_PASSWORD",""));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver"); jdbc=new JdbcTemplate(source);
        assertEquals("records_p2_test",jdbc.queryForObject("SELECT DATABASE()",String.class),"Dedicated test DB required");
        for(String table:List.of("game_session_players","game_record_versions","game_round_scores","game_rounds","game_sessions","user"))
            jdbc.execute("DROP TABLE IF EXISTS `"+table+"`");
        new SchemaInitializer(jdbc).initSchema();
        MybatisConfiguration configuration=new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        configuration.addInterceptor(new QueryCounter());
        for(Class<?> mapper:List.of(GameSessionsMapper.class,GameRoundsMapper.class,RecordsV2Mapper.class,UserMapper.class)) configuration.addMapper(mapper);
        MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        SqlSessionFactory sqlFactory=factory.getObject(); SqlSessionTemplate sql=new SqlSessionTemplate(sqlFactory);
        sessions=sql.getMapper(GameSessionsMapper.class); GameRoundsMapper rounds=sql.getMapper(GameRoundsMapper.class);
        transactions=new DataSourceTransactionManager(source);
        projection=new RecordProjection(jdbc); ReflectionTestUtils.setField(projection,"readEnabled",true);
        RecordDetailCache cache=new RecordDetailCache(512,65536,8388608,60000);
        RecordsServiceImpl legacy=new RecordsServiceImpl(sessions,rounds,sql.getMapper(UserMapper.class));
        RecordsV2Service target=new RecordsV2Service(sessions,sql.getMapper(RecordsV2Mapper.class),legacy); target.setP2(projection,cache);
        service=proxy(target);
        RoundPersister writer=new RoundPersister(sessions,rounds); writer.setRecordProjection(projection); writer.setRecordsV2Service(service);
        persist=proxy(writer); migration=new RecordsP2Migration(source);
        jdbc.update("INSERT INTO `user`(id,username,password,nickname) VALUES(7,'seven','test','七'),(8,'eight','test','八')");
        sid=persist.createSession("123456",7,List.of(7L,8L,-2L,-3L));
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        ProxyFactory factory=new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
    private List<GameRoundsMapper.ScoreRow> scores(int amount) {
        return List.of(new GameRoundsMapper.ScoreRow(7,0,3*amount,true,"{}"),new GameRoundsMapper.ScoreRow(8,1,-amount,false,"{}"),
                new GameRoundsMapper.ScoreRow(-2,2,-amount,false,"{}"),new GameRoundsMapper.ScoreRow(-3,3,-amount,false,"{}"));
    }
    private long append(int number,int amount,int fan) {
        return persist.saveHand(sid,number,false,0,7L,null,1,"{\"hand\":[1,2],\"melds\":[]}","{\"PING_HU\":"+fan+"}",fan,"EAST",0,scores(amount));
    }
    @ParameterizedTest @ValueSource(ints={10,100,1000}) void longHistoryMatchesAndIndexedReadsStaySmall(int count) {
        long started=System.nanoTime();
        for(int n=1;n<=count;n++) append(n,2,2);
        migration.promote(); assertTrue(projection.reads());
        queryIds.clear();
        var overview=service.overview(sid,7,null); assertEquals(6L*count,overview.getTotalScore()); assertEquals(count,overview.getWinCount());
        var list=service.list(7,1,10,null); assertEquals(6*count,((RecordsListVO)((List<?>)list.get("records")).get(0)).getTotalScore());
        assertFalse(queryIds.stream().anyMatch(id->id.endsWith(".stats") || id.endsWith(".selectTotals") || id.endsWith(".highestFan")));
        assertEquals(2,queryIds.stream().filter(id->id.endsWith(".recordNames")).count());
        assertEquals(6L*5,service.overview(sid,7,5).getTotalScore());
        assertEquals(4,service.rounds(sid,7,0,10,count).getRounds().get(0).getParticipants().size());
        migration.batch(0,100,true); migration.batch(0,100,true); migration.batch(0,100,false);
        assertEquals(6L*count,service.overview(sid,7,null).getTotalScore());
        System.out.printf("[P2 SQL] history=%d elapsedMs=%.1f indexedPlan=%s%n",count,(System.nanoTime()-started)/1e6,
                jdbc.queryForList("EXPLAIN SELECT session_id FROM game_session_players WHERE user_id=7 ORDER BY session_id DESC LIMIT 10"));
    }
    @Test void replayRollbackAndFreshSettlement() {
        long first=append(1,2,2); var before=service.overview(sid,7,null);
        assertEquals(first,append(1,99,99)); assertEquals(6,service.overview(sid,7,null).getTotalScore());
        assertThrows(RuntimeException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status -> { append(2,2,2); throw new IllegalStateException("rollback"); }));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_rounds WHERE session_id=?",Integer.class,sid));
        assertEquals(6,service.overview(sid,7,null).getTotalScore());
        append(2,3,4); var after=service.overview(sid,7,null);
        assertEquals(15,after.getTotalScore()); assertNotEquals(before.getVersion(),after.getVersion());
        migration.batch(0,100,false);
    }
    @Test void crossPlayerCacheAndUnauthorizedRead() {
        append(1,2,2);
        RecordRoundVO seven=(RecordRoundVO)service.detail(sid,7,1,null).get("round");
        RecordRoundVO eight=(RecordRoundVO)service.detail(sid,8,1,null).get("round");
        assertTrue(seven.getParticipants().stream().filter(p->p.getUserId()==7).findFirst().orElseThrow().isMe());
        assertFalse(eight.getParticipants().stream().filter(p->p.getUserId()==7).findFirst().orElseThrow().isMe());
        assertTrue(eight.getParticipants().stream().filter(p->p.getUserId()==8).findFirst().orElseThrow().isMe());
        assertThrows(hainanMahjong.exception.BusinessException.class,()->service.detail(sid,999,1,null));
        jdbc.update("UPDATE `user` SET nickname='新昵称' WHERE id=7");
        assertEquals("新昵称",((RecordRoundVO)service.detail(sid,8,1,null).get("round")).getParticipants().get(0).getNickname());
    }
    @Test void migrationGateAndCorrectionsAndEmptySessions() {
        assertFalse(projection.reads()); assertEquals(1L,service.list(7,1,10,null).get("total"));
        migration.promote(); assertTrue(projection.reads()); assertEquals(0,service.overview(sid,7,null).getTotalScore());
        append(1,2,2); service.overview(sid,7,null); service.detail(sid,7,1,null);
        jdbc.update("UPDATE game_round_scores s JOIN game_rounds r ON r.id=s.round_id SET s.score_change=30 WHERE r.session_id=? AND s.user_id=7",sid);
        assertThrows(IllegalStateException.class,migration::promote); assertFalse(projection.reads());
        migration.batch(0,100,true); migration.promote();
        assertEquals(30,service.overview(sid,7,null).getTotalScore());
        assertEquals(30,((RecordRoundVO)service.detail(sid,7,1,null).get("round")).getParticipants().get(0).getScoreChange());
    }
    @Test void rejectedRoundRollsBackDetailsAndProjectionAndOlderInsertionKeepsFanTie() {
        append(2,2,2); append(1,2,2);
        migration.batch(0,100,false); migration.promote();
        assertEquals(12,service.overview(sid,7,null).getTotalScore());
        var wrong=List.of(new GameRoundsMapper.ScoreRow(999,0,6,true,"{}"),new GameRoundsMapper.ScoreRow(8,1,-2,false,"{}"),
                new GameRoundsMapper.ScoreRow(-2,2,-2,false,"{}"),new GameRoundsMapper.ScoreRow(-3,3,-2,false,"{}"));
        assertThrows(IllegalArgumentException.class,()->persist.saveHand(sid,3,false,0,999L,null,1,"{}","{}",2,"EAST",0,wrong));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM game_rounds",Integer.class));
        assertEquals(12,service.overview(sid,7,null).getTotalScore());
    }
    @Test void simultaneousBackfillAndSettlementAreSerialized() throws Exception {
        append(1,2,2); ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<?> backfill=workers.submit(()->migration.batch(0,100,true));
            Future<?> settlement=workers.submit(()->append(2,2,2));
            backfill.get(10,TimeUnit.SECONDS); settlement.get(10,TimeUnit.SECONDS);
            migration.batch(0,100,false); migration.promote(); assertEquals(12,service.overview(sid,7,null).getTotalScore());
        } finally { workers.shutdownNow(); }
    }
    @Test void resumedDualWriteRebuildsProgressMissingDuringRollback() {
        append(1,2,2);
        ReflectionTestUtils.setField(projection,"writeEnabled",false);
        append(2,2,2);
        ReflectionTestUtils.setField(projection,"writeEnabled",true);
        append(3,2,2);
        migration.batch(0,100,false); migration.promote();
        assertEquals(18,service.overview(sid,7,null).getTotalScore());
    }
}
