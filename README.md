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

- 使用 gradlew 编译：`产物只有so`

```
.\gradlew :module:externalNativeBuildRelease
```

```
module_id
└── build
    └── intermediates
        └── ndkBuild
            └── release
                └── obj
                    └── local
                        ├── arm64-v8a
                        ├── armeabi-v7a
                        ├── x86
                        └── x86_64
```

- 使用 gradlew 编译：`产物有so`和`magisk`对应的zip

```
./gradlew packageMagiskZip
```

```
module_id
└── build
    └── outputs
        └── magisk
```


- 新的编译命令： 
```
./gradlew clean :module:assemble
```