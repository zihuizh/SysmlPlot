package ParamModel {
    private import ScalarValues::*;

    attribute def Scene;
    attribute def Image;

    // 带方向的参数应以 `parameters` 仓格呈现
    action def Focus {
        in scene : Scene;
        out image : Image;
    }

    action def Shoot {
        in image : Image;
        out picture : Image;
    }

    part def Camera;

    // 动作用法（perform 需要引用用法，不能直接引用 action def）
    action focusAction : Focus;

    // 执行：部件 perform 一个动作用法
    part camera : Camera {
        perform focusAction;
    }

    // 带值的属性应以 `values` 仓格呈现
    attribute def Settings {
        attribute exposure : Integer = 100;
        attribute mode : Scene;
    }
}
