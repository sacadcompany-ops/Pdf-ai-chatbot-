package com.example.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GroqApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authHeader: String,
        @Body request: OpenAiChatRequest
    ): Response<OpenAiChatResponse>
}

interface CloudflareApi {
    @POST("client/v4/accounts/{account_id}/ai/v1/chat/completions")
    suspend fun chatCompletions(
        @Path("account_id") accountId: String,
        @Header("Authorization") authHeader: String,
        @Body request: OpenAiChatRequest
    ): Response<OpenAiChatResponse>
}

interface OpenRouterApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authHeader: String,
        @Header("HTTP-Referer") referer: String,
        @Header("X-Title") title: String,
        @Body request: OpenAiChatRequest
    ): Response<OpenAiChatResponse>
}

interface AnyRouterApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authHeader: String,
        @Body request: OpenAiChatRequest
    ): Response<OpenAiChatResponse>
}

interface GeminiRestApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKeyHeader: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContentWithKey(
        @Path("model") model: String,
        @Query("key") apiKeyQuery: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>
}
