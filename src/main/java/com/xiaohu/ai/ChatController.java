package com.xiaohu.ai;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    //构造函数spring自动把chatclient.Builder注入进来
    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/chat")
    @PreAuthorize("hasAuthority('chat:ask')")
        public String chat(@RequestParam String message){


        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

}
