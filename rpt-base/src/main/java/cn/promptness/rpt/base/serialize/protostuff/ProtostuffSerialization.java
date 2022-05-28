package cn.promptness.rpt.base.serialize.protostuff;

import cn.promptness.rpt.base.serialize.api.ObjectInputStream;
import cn.promptness.rpt.base.serialize.api.ObjectOutputStream;
import cn.promptness.rpt.base.serialize.api.Serialization;
import cn.promptness.rpt.base.serialize.api.SerializationType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ProtostuffSerialization implements Serialization {

    @Override
    public SerializationType getType() {
        return SerializationType.PROTOSTUFF;
    }

    @Override
    public ObjectOutputStream serialize(OutputStream output) throws IOException {
        return new ProtostuffDataOutputStream(output);
    }

    @Override
    public ObjectInputStream deserialize(InputStream input) throws IOException {
        return new ProtostuffDataInputStream(input);
    }
}
