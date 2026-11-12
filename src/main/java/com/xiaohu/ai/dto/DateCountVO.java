package com.xiaohu.ai.dto;

/** 按天统计：近 7 天上传趋势 {date=2026-11-12, count=2} */
public class DateCountVO {

    private String date;
    private Long count;

    public DateCountVO() {
    }

    public DateCountVO(String date, Long count) {
        this.date = date;
        this.count = count;
    }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public Long getCount() { return count; }
    public void setCount(Long count) { this.count = count; }
}
