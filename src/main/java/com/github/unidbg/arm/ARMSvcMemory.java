package com.github.unidbg.arm;

import com.github.unidbg.Emulator;
import com.github.unidbg.Svc;
import com.github.unidbg.memory.MemRegion;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnicornPointer;
import com.github.unidbg.spi.SyscallHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import unicorn.Unicorn;
import unicorn.UnicornConst;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ARMSvcMemory implements SvcMemory {

    private static final Log log = LogFactory.getLog(ARMSvcMemory.class);

    private final Emulator emulator;
    private UnicornPointer base;

    ARMSvcMemory(Unicorn unicorn, long base, int size, Emulator emulator) {
        this.emulator = emulator;
        this.base = UnicornPointer.pointer(emulator, base);
        assert this.base != null;
        this.base.setSize(size);

        unicorn.mem_map(base, size, UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_EXEC);
    }

    private final List<MemRegion> memRegions = new ArrayList<>();

    @Override
    public MemRegion findRegion(long addr) {
        for (MemRegion region : memRegions) {
            if (addr >= region.begin && addr < region.end) {
                return region;
            }
        }
        return null;
    }

    @Override
    public UnicornPointer allocate(int size, final String label) {
        size = ARM.alignSize(size);
        UnicornPointer pointer = base.share(0, size);
        base = (UnicornPointer) base.share(size);
        if (log.isDebugEnabled()) {
            log.debug("allocate size=" + size + ", label=" + label + ", base=" + base);
        }
        memRegions.add(new MemRegion(pointer.peer, pointer.peer + size, UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_EXEC, null, 0) {
            @Override
            public String getName() {
                return label;
            }
        });
        return pointer;
    }

    private int thumbSvcNumber = 0;
    private int armSvcNumber = 0xff;

    private final Map<Integer, Svc> svcMap = new HashMap<>();

    @Override
    public Svc getSvc(int svcNumber) {
        return svcMap.get(svcNumber);
    }

    @Override
    public UnicornPointer registerSvc(Svc svc) {
        final int number;
        if (svc instanceof ThumbSvc) {
            if (emulator.is64Bit()) {
                throw new IllegalStateException("is 64 bit mode");
            }

            if (++thumbSvcNumber == SyscallHandler.DARWIN_SWI_SYSCALL) {
                thumbSvcNumber++;
            }
            number = thumbSvcNumber;
        } else if (svc instanceof ArmSvc || svc instanceof Arm64Svc) {
            if (svc instanceof ArmSvc && emulator.is64Bit()) {
                throw new IllegalStateException("is 64 bit mode");
            }
            if (svc instanceof Arm64Svc && !emulator.is64Bit()) {
                throw new IllegalStateException("is 32 bit mode");
            }

            if (++armSvcNumber == SyscallHandler.DARWIN_SWI_SYSCALL) {
                armSvcNumber++;
            }
            number = armSvcNumber;
        } else {
            throw new IllegalStateException("svc=" + svc);
        }
        if (svcMap.put(number, svc) != null) {
            throw new IllegalStateException();
        }
        return svc.onRegister(this, number);
    }

}
