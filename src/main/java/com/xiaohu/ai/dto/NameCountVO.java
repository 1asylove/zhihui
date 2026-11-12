package com.xiaohu.ai.dto;

/** 分组统计通用结构：如按文件类型分布 {name=txt, count=3} */
public class NameCountVO {

    private String name;
    private Long count;

    public NameCountVO() {
    }

    public NameCountVO(String name, Long count) {
        this.name = name;
        this.count = count;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getCount() { return count; }
    public void setCount(Long count) { this.count = count; }
}
