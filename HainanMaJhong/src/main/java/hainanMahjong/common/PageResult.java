package hainanMahjong.common;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    private Long total;
    private Long pageNum;
    private Long pageSize;
    private Long pages;
    private List<T> records;

}

