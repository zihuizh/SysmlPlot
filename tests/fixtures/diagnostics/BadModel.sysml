// 反例模型：故意包含语义错误，用于验证诊断链路。
// 该目录不参与 pre-commit 的 samples 校验（钩子只校验 samples/**）。
//
// 期望被报告的问题（实测，2026-09-10，Pilot 0.57.0）：
//   line 23  Couldn't resolve reference to Type 'MissingType'   （链接错误，parse 与 validation 各报一次）
//   line 23  An occurrence, item or part must be typed by occurrence definitions  （纯语义规则）
//   line 19  Duplicate of other owned member name               （纯语义规则）
//   line 20  Duplicate of other owned member name               （纯语义规则）
//   合计：errors=3（其中 1 条重复） warnings=2
package BadModel {
    private import ScalarValues::*;

    part def Vehicle {
        part engine : Engine;
    }

    part def Engine;

    part vehicle : Vehicle {
        attribute mass : Real;
        part mass : Engine;
    }

    part broken : MissingType;
}
