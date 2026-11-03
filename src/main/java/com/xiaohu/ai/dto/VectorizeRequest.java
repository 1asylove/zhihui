package com.xiaohu.ai.dto;

import java.util.List;

/**
 * 向量化请求：只传文本，不带文档 id，保持 Python 服务无状态。
 */
public class VectorizeRequest {

    private List<String> texts;
    private String model;

    public VectorizeRequest() {
    }

    public VectorizeRequest(List<String> texts) {
        this.texts = texts;
    }

    public List<String> getTexts() {
        return texts;
    }

    public void setTexts(List<String> texts) {
        this.texts = texts;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
