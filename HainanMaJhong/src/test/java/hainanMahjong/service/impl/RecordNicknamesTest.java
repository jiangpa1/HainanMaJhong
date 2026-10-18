package hainanMahjong.service.impl;

import hainanMahjong.mapper.*;
import hainanMahjong.pojo.User;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordNicknamesTest {
    @Test void batchesDistinctHumansAndKeepsFallbacksWithoutReadingWholeUsers() {
        UserMapper users=mock(UserMapper.class);
        User named=new User(); named.setId(7L); named.setNickname("七");
        User unnamed=new User(); unnamed.setId(8L); unnamed.setUsername("eight");
        when(users.recordNames(List.of(7L,8L,9L))).thenReturn(List.of(named,unnamed));
        RecordsServiceImpl records=new RecordsServiceImpl(mock(GameSessionsMapper.class),mock(GameRoundsMapper.class),users);
        Map<Long,String> names=records.nicknames(List.of(7L,8L,7L,9L,-1L,0L));
        assertEquals("七",names.get(7L)); assertEquals("eight",names.get(8L)); assertEquals("玩家9",names.get(9L));
        assertEquals("电脑·南",names.get(-1L)); assertEquals("游客",names.get(0L));
        verify(users).recordNames(List.of(7L,8L,9L)); verifyNoMoreInteractions(users);
    }
}
