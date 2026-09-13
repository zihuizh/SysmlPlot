package ActionModel {
    private import ScalarValues::*;

    attribute def Image;

    action def Focus {
        out picture : Image;
    }

    action def Shoot {
        in picture : Image;
        out photo : Image;
    }

    // 一个动作里同时有：显式 succession、`first ... then ...`、跨动作的数据流
    action capture {
        action setup;
        action focus : Focus;
        action shoot : Shoot;

        first setup then focus;
        succession focusThenShoot first focus then shoot;

        flow of Image from focus.picture to shoot.picture;
    }

    part def Camera;

    // 部件执行整个动作：perform 边的来源
    part camera : Camera {
        perform capture;
    }
}
