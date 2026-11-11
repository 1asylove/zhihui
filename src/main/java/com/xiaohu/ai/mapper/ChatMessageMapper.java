package com.xiaohu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohu.ai.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
