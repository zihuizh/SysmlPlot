package ExposeViews {
    private import ExposeModel::*;
    private import StandardViewDefinitions::*;

    // 视图定义继承：filter 写在基定义上，派生定义不带条件
    view def 'Structure Base' :> GeneralView {
        filter @SysML::PartUsage or @SysML::PartDefinition;
    }

    view def 'Structure Derived' :> 'Structure Base';

    // 1) 成员 expose：只暴露点名的元素
    view 'membership' : 'Structure Derived' {
        expose vehicle;
        expose Engine;
    }

    // 2) 命名空间 expose：只取直接成员
    view 'namespace' : 'Structure Derived' {
        expose ExposeModel::*;
    }

    // 3) 递归 expose：连同嵌套命名空间一起
    view 'recursive' : 'Structure Derived' {
        expose vehicle::**;
        expose ExposeModel::Vehicle;
    }
}
