fun <T> doSomething(a: T) {}

fun foo(i: Int) {
    if (i == 1)
        doSomething(1)
    <caret>else
        doSomething(0)

    doSomething(-1)
}