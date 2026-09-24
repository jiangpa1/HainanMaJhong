package hainanMahjong.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
public class PageQueryDTO {
    @Min(value = 1, message = "从第一页开始访问")
    private Integer pageNum = 1;
    @Min(value = 1, message = "一页只能访问1-20条数据")
    @Max(value = 20, message = "一页只能访问1-20条数据")
    private Integer pageSize = 10;
}
