package io.github.aiarchguard.platform.identity;

/** 向业务应用层提供当前可信操作者，隔离 Spring Security 等身份实现细节。 */
public interface CurrentActorProvider {

    CurrentActor currentActor();
}
