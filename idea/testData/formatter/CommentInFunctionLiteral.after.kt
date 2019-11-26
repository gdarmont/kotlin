fun test(some: (Int) -> Int) {
}

fun foo() {
    test() {
        // Some comment
        it
    }

    test() {
//        val a = 42
    }

    test() {
/*
        val a = 42
*/
    }

    test() {
        /*
            val a = 42
        */
    }

    test() {
//        val a = 42
        val b = 44
    }
}