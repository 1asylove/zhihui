package com.xiaohu.ai.dto;

import java.util.List;

/**
 * 向量化响应：vectors 与请求 texts 顺序一一对应。
 */
public class VectorizeResponse {

    private String model;
    private Integer dimension;
    private List<List<Float>> vectors;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getDimension() {
        return dimension;
    }

    public void setDimension(Integer dimension) {
        this.dimension = dimension;
    }

    public List<List<Float>> getVectors() {
        return vectors;
    }

    public void setVectors(List<List<Float>> vectors) {
        this.vectors = vectors;
    }
}
