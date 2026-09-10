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

    // 带值的属性应以 `values` 仓格呈现
    attribute def Settings {
        attribute exposure : Integer = 100;
        attribute mode : Scene;
    }
}
