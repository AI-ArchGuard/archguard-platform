package io.github.aiarchguard.platform.governance;

/**
 * Finding 集合的相对分类：候选为 C、参考为 B 时，NEW=C−B、EXISTING=C∩B、RESOLVED=B−C。
 * 基线比较以不可变基线为参考；PR 修订差异以先前可信修订为参考，调用方必须保留这个区别。
 */
public enum Classification { NEW, EXISTING, RESOLVED }
