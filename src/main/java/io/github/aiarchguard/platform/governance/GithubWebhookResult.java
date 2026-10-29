package io.github.aiarchguard.platform.governance;

import java.util.UUID;

/**
 * Webhook 接收结果，不返回原始正文、签名或 Secret；调用者可据此区分生效、忽略和重放。
 *
 * @param deliveryId GitHub 投递 UUID，也是正文摘要的幂等绑定键
 * @param disposition 已记录的投递处置，同 ID 同正文重放沿用原值
 * @param replay 是否命中已有投递；不表示再次应用了 PR 更新
 */
public record GithubWebhookResult(UUID deliveryId, GithubWebhookDisposition disposition, boolean replay) { }
