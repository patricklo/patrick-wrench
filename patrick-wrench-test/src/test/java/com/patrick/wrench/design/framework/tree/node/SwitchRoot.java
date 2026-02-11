package com.patrick.wrench.design.framework.tree.node;

import com.patrick.wrench.design.framework.tree.AbstractXxxSupport;
import com.patrick.wrench.design.framework.tree.StrategyHandler;
import com.patrick.wrench.design.framework.tree.factory.DefaultStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SwitchRoot extends AbstractXxxSupport {

    @Autowired
    private AccountNode accountNode;

    @Override
    protected String doApply(String requestParameter, DefaultStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【开关节点】规则决策树 userId:{}", requestParameter);
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<String, DefaultStrategyFactory.DynamicContext, String> get(String requestParameter, DefaultStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return accountNode;
    }

}
