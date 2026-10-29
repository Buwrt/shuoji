package com.violet.toolbox.core;

import java.io.IOException;
import java.io.InputStream;

/**
 * 镜像数据源抽象：可来自 Uri（SAF 选择）、本地文件或缓存文件。
 */
public interface StreamSource {

    InputStream open() throws IOException;

    long size();

    String name();
}
