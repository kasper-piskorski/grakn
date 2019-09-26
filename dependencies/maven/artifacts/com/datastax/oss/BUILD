java_library(
    name = "java-driver-core",
    exports = [
        "//external:jar/com/datastax/oss/java-driver-core"
    ],
    runtime_deps = [
        "//dependencies/maven/artifacts/com/github/jnr:jnr-posix",
        "//dependencies/maven/artifacts/com/github/spotbugs:spotbugs-annotations",
        "//dependencies/maven/artifacts/com/github/stephenc/jcip:jcip-annotations",
        "//dependencies/maven/artifacts/com/typesafe:config",
        "//dependencies/maven/artifacts/io/dropwizard/metrics:metrics-core",
        "//dependencies/maven/artifacts/io/netty:netty-handler",
        "//dependencies/maven/artifacts/org/hdrhistogram:HdrHistogram",
        ":java-driver-shaded-guava",
        ":native-protocol"
    ],
    visibility = [
        "//dependencies/maven/artifacts:__subpackages__"
    ]
)



java_library(
    name = "java-driver-query-builder",
    exports = [
        "//external:jar/com/datastax/oss/java-driver-query-builder"
    ],
    runtime_deps = [
        "//dependencies/maven/artifacts/com/github/spotbugs:spotbugs-annotations",
        "//dependencies/maven/artifacts/com/github/stephenc/jcip:jcip-annotations",
        ":java-driver-core",
        ":java-driver-shaded-guava"
    ],
    visibility = [
        "//dependencies/maven/artifacts:__subpackages__"
    ]
)



java_library(
    name = "java-driver-shaded-guava",
    exports = [
        "//external:jar/com/datastax/oss/java-driver-shaded-guava"
    ],
    visibility = [
        "//dependencies/maven/artifacts:__subpackages__"
    ]
)



java_library(
    name = "native-protocol",
    exports = [
        "//external:jar/com/datastax/oss/native-protocol"
    ],
    visibility = [
        "//dependencies/maven/artifacts:__subpackages__"
    ]
)


