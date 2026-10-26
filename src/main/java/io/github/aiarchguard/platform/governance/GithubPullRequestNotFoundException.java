package io.github.aiarchguard.platform.governance;

/**
 * 指定 Project/Repository 中没有可读取的 GitHub PR 状态，HTTP 层返回治理资源 404。
 * PR 状态来自已应用的验签事件；报告中的 PR 断言本身不会创建可信当前 head。
 */
public class GithubPullRequestNotFoundException extends RuntimeException { }
