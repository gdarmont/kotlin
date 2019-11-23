// OUT_OF_CODE_BLOCK: FALSE
// RUNTIME
// TYPE: t

fun twice(s: String): String {
    val repeatFun: String.(Int) -> String = { t -> this.repeat(<caret>) }

    return repeatFun(s, 2)
}
