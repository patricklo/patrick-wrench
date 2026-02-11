package com.patrick.wrench.design.framework.tree;

/**
 * 用于获取下一个handler
 * @param <T>
 * @param <D>
 * @param <R>
 */

public interface StrategyMapper <T, D, R> {
    StrategyHandler<T, D, R> get(T requestParam, D dynamicContext) throws Exception;
}
