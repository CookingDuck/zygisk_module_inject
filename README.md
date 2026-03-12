## Building

- 模块模板

```
module_id
├── module.prop
└── zygisk
    ├── arm64-v8a.so
    ├── armeabi-v7a.so
    ├── x86.so
    └── x86_64.so
```

- 使用 gradlew 编译：`产物有so`和`magisk`对应的zip

> ./gradlew zipRelease 
> 
> ./gradlew :module:zipDebug // 编译debug模式，ui不会有签名问题，


```text
module_id
└── build
    └── outputs
        └── magisk

```



- 清除编译
> ./gradlew clean :module:assemble




- 如果报错环境问题，可以指定项目jdk版本，在根目录的`gradle.properties`添加 `org.gradle.java.home=C:\\Program Files\\Java\\jdk-17`指定版本