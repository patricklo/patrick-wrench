package com.patrick.wrench.design.framework.tree;

import lombok.Getter;
import lombok.Setter;

public abstract class AbstractStrategyRouter<T, D, R> implements StrategyHandler<T, D, R>, StrategyMapper<T, D, R>{
    @Getter
    @Setter
    protected StrategyHandler<T, D, R > defaultStrategyHandler = StrategyHandler.DEFAULT;

    public R router(T requestParam, D dynamicContext) throws Exception {
        StrategyHandler<T, D, R> strategyHandler = get(requestParam, dynamicContext);
        if (null != strategyHandler) return strategyHandler.apply(requestParam,dynamicContext);

        return defaultStrategyHandler.apply(requestParam, dynamicContext);
    }
}
