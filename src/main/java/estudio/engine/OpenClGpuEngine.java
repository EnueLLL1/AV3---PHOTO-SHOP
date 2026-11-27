package estudio.engine;

import estudio.core.ImageBuffer;
import estudio.model.FilterType;
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;
import org.jocl.cl_queue_properties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class OpenClGpuEngine implements ImageEngine, AutoCloseable {
    public static final String ENGINE_NAME = "GPU_OPENCL";
    private static final String KERNEL_RESOURCE = "/kernels/filters.cl";
    private static final Path KERNEL_FALLBACK_PATH = Path.of("src", "main", "resources", "kernels", "filters.cl");
    private static final String DEVICE_TYPE_PROPERTY = "estudio.opencl.deviceType";

    private record SelectedDevice(cl_platform_id platform, cl_device_id device) {
    }

    private cl_context cachedContext;
    private cl_command_queue cachedQueue;
    private cl_program cachedProgram;
    private String cachedDeviceName;
    private String cachedDeviceTypeLabel;
    private int cachedWorkers;

    public String engineName() {
        return ENGINE_NAME;
    }

    public static boolean isOpenClAvailable() {
        try {
            CL.setExceptionsEnabled(true);
            return selectDevice(System.getProperty(DEVICE_TYPE_PROPERTY)) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public synchronized EngineResult process(
            List<Path> inputFiles,
            Path outputDir,
            FilterType filter,
            ProgressListener listener) throws Exception {
        Objects.requireNonNull(inputFiles, "inputFiles must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(filter, "filter must not be null");

        Files.createDirectories(outputDir);
        CL.setExceptionsEnabled(true);

        long totalStartNs = System.nanoTime();
        long kernelNs = 0L;
        long transferNs = 0L;

        ensureInitialized();

        cl_kernel kernel = null;
        try {
            kernel = CL.clCreateKernel(cachedProgram, kernelNameFor(filter), null);

            int total = inputFiles.size();
            for (int i = 0; i < total; i++) {
                Path inputFile = Objects.requireNonNull(inputFiles.get(i), "inputFiles element must not be null");
                ImageBuffer src = ImageBuffer.read(inputFile);
                ImageBuffer dst = src.cloneEmpty();

                cl_mem srcMem = null;
                cl_mem dstMem = null;
                try {
                    long byteSize = (long) src.pixels.length * Sizeof.cl_int;
                    long h2dStartNs = System.nanoTime();
                    srcMem = CL.clCreateBuffer(cachedContext, CL.CL_MEM_READ_ONLY, byteSize, null, null);
                    dstMem = CL.clCreateBuffer(cachedContext, CL.CL_MEM_WRITE_ONLY, byteSize, null, null);
                    CL.clEnqueueWriteBuffer(
                            cachedQueue, srcMem, CL.CL_TRUE, 0, byteSize, Pointer.to(src.pixels), 0, null, null);
                    transferNs += System.nanoTime() - h2dStartNs;

                    CL.clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(srcMem));
                    CL.clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(dstMem));
                    CL.clSetKernelArg(kernel, 2, Sizeof.cl_int, Pointer.to(new int[]{src.width}));
                    CL.clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[]{src.height}));

                    long[] globalWorkSize = new long[]{src.width, src.height};
                    long kernelStartNs = System.nanoTime();
                    CL.clEnqueueNDRangeKernel(cachedQueue, kernel, 2, null, globalWorkSize, null, 0, null, null);
                    CL.clFinish(cachedQueue);
                    kernelNs += System.nanoTime() - kernelStartNs;

                    long d2hStartNs = System.nanoTime();
                    CL.clEnqueueReadBuffer(
                            cachedQueue, dstMem, CL.CL_TRUE, 0, byteSize, Pointer.to(dst.pixels), 0, null, null);
                    transferNs += System.nanoTime() - d2hStartNs;
                } finally {
                    releaseMemQuietly(dstMem);
                    releaseMemQuietly(srcMem);
                }

                String outName = BatchEngineSupport.outputFileName(inputFile);
                dst.writePng(outputDir.resolve(outName));

                if (listener != null) {
                    listener.onProgress(i + 1, total, outName);
                }
            }
        } finally {
            releaseKernelQuietly(kernel);
        }

        long computeNs = kernelNs + transferNs;
        long totalNs = System.nanoTime() - totalStartNs;
        long kernelMs = TimeUnit.NANOSECONDS.toMillis(kernelNs);
        long transferMs = TimeUnit.NANOSECONDS.toMillis(transferNs);
        String details = cachedDeviceName
                + " (" + cachedDeviceTypeLabel
                + ", kernelMs=" + kernelMs
                + ", transferMs=" + transferMs + ")";
        return new EngineResult(
                engineName(),
                cachedWorkers,
                TimeUnit.NANOSECONDS.toMillis(computeNs),
                TimeUnit.NANOSECONDS.toMillis(totalNs),
                details);
    }

    private void ensureInitialized() throws IOException {
        if (cachedProgram != null && cachedQueue != null && cachedContext != null) {
            return;
        }
        SelectedDevice selected = selectDevice(System.getProperty(DEVICE_TYPE_PROPERTY));
        if (selected == null) {
            throw new IllegalStateException("No OpenCL device available");
        }
        String kernelSource = loadKernelSource();
        String deviceName = getDeviceString(selected.device, CL.CL_DEVICE_NAME);
        String deviceTypeLabel = getDeviceTypeLabel(selected.device);
        int workers = Math.max(1, getDeviceInt(selected.device, CL.CL_DEVICE_MAX_COMPUTE_UNITS));

        cl_context context = null;
        cl_command_queue queue = null;
        cl_program program = null;
        try {
            cl_context_properties contextProps = new cl_context_properties();
            contextProps.addProperty(CL.CL_CONTEXT_PLATFORM, selected.platform);

            context = CL.clCreateContext(
                    contextProps,
                    1,
                    new cl_device_id[]{selected.device},
                    null,
                    null,
                    null);
            queue = createCommandQueue(context, selected.device);
            program = CL.clCreateProgramWithSource(context, 1, new String[]{kernelSource}, null, null);
            buildProgram(program, selected.device);

            cachedContext = context;
            cachedQueue = queue;
            cachedProgram = program;
            cachedDeviceName = deviceName;
            cachedDeviceTypeLabel = deviceTypeLabel;
            cachedWorkers = workers;
        } catch (RuntimeException e) {
            releaseProgramQuietly(program);
            releaseQueueQuietly(queue);
            releaseContextQuietly(context);
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        releaseProgramQuietly(cachedProgram);
        releaseQueueQuietly(cachedQueue);
        releaseContextQuietly(cachedContext);
        cachedProgram = null;
        cachedQueue = null;
        cachedContext = null;
    }

    private static String kernelNameFor(FilterType filter) {
        return switch (filter) {
            case BLUR -> "blur_5x5";
            case SHARPEN -> "sharpen_3x3";
            case SOBEL -> "sobel_3x3";
        };
    }

    private static String loadKernelSource() throws IOException {
        try (InputStream is = OpenClGpuEngine.class.getResourceAsStream(KERNEL_RESOURCE)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (Files.isRegularFile(KERNEL_FALLBACK_PATH)) {
            return Files.readString(KERNEL_FALLBACK_PATH, StandardCharsets.UTF_8);
        }
        throw new IOException(
                "OpenCL kernel source not found on classpath (" + KERNEL_RESOURCE
                        + ") or filesystem (" + KERNEL_FALLBACK_PATH + ")");
    }

    private static SelectedDevice selectDevice(String requestedType) {
        long[] targetTypes = resolveDeviceSearchOrder(requestedType);

        int[] numPlatformsArray = new int[1];
        try {
            CL.clGetPlatformIDs(0, null, numPlatformsArray);
        } catch (CLException e) {
            return null;
        }
        int numPlatforms = numPlatformsArray[0];
        if (numPlatforms <= 0) {
            return null;
        }

        cl_platform_id[] platforms = new cl_platform_id[numPlatforms];
        CL.clGetPlatformIDs(platforms.length, platforms, null);

        for (long targetType : targetTypes) {
            for (cl_platform_id platform : platforms) {
                int[] numDevicesArray = new int[1];
                try {
                    CL.clGetDeviceIDs(platform, targetType, 0, null, numDevicesArray);
                } catch (CLException e) {
                    continue;
                }
                if (numDevicesArray[0] <= 0) {
                    continue;
                }
                cl_device_id[] devices = new cl_device_id[numDevicesArray[0]];
                CL.clGetDeviceIDs(platform, targetType, devices.length, devices, null);
                for (cl_device_id device : devices) {
                    if (isDeviceAvailable(device)) {
                        return new SelectedDevice(platform, device);
                    }
                }
            }
        }
        return null;
    }

    private static long[] resolveDeviceSearchOrder(String requestedType) {
        if (requestedType == null || requestedType.isBlank()) {
            return new long[]{CL.CL_DEVICE_TYPE_GPU, CL.CL_DEVICE_TYPE_CPU, CL.CL_DEVICE_TYPE_ALL};
        }
        return switch (requestedType.trim().toLowerCase(Locale.ROOT)) {
            case "gpu" -> new long[]{CL.CL_DEVICE_TYPE_GPU};
            case "cpu" -> new long[]{CL.CL_DEVICE_TYPE_CPU};
            case "all" -> new long[]{CL.CL_DEVICE_TYPE_GPU, CL.CL_DEVICE_TYPE_CPU, CL.CL_DEVICE_TYPE_ALL};
            default -> throw new IllegalArgumentException(
                    "Invalid " + DEVICE_TYPE_PROPERTY + " value: " + requestedType + " (expected gpu, cpu, or all)");
        };
    }

    private static boolean isDeviceAvailable(cl_device_id device) {
        int[] available = new int[1];
        CL.clGetDeviceInfo(device, CL.CL_DEVICE_AVAILABLE, Sizeof.cl_int, Pointer.to(available), null);
        return available[0] != 0;
    }

    private static cl_command_queue createCommandQueue(cl_context context, cl_device_id device) {
        try {
            cl_command_queue queue = CL.clCreateCommandQueueWithProperties(
                    context, device, new cl_queue_properties(), null);
            if (queue != null) {
                return queue;
            }
        } catch (CLException | LinkageError ignored) {
            // Fallback abaixo para drivers OpenCL 1.2 sem clCreateCommandQueueWithProperties.
        }
        return createCommandQueueLegacy(context, device);
    }

    // Fallback para drivers OpenCL 1.2 (ex.: versoes antigas do PoCL) que nao suportam clCreateCommandQueueWithProperties.
    @SuppressWarnings("deprecation")
    private static cl_command_queue createCommandQueueLegacy(cl_context context, cl_device_id device) {
        return CL.clCreateCommandQueue(context, device, 0L, null);
    }

    private static void buildProgram(cl_program program, cl_device_id device) {
        try {
            CL.clBuildProgram(program, 1, new cl_device_id[]{device}, null, null, null);
        } catch (CLException e) {
            String log = getBuildLog(program, device);
            throw new CLException("Failed to build OpenCL program: " + e.getMessage() + "\n" + log, e.getStatus());
        }
    }

    private static String getBuildLog(cl_program program, cl_device_id device) {
        try {
            long[] logSize = new long[1];
            CL.clGetProgramBuildInfo(program, device, CL.CL_PROGRAM_BUILD_LOG, 0, null, logSize);
            if (logSize[0] <= 1) {
                return "";
            }
            byte[] buffer = new byte[(int) logSize[0]];
            CL.clGetProgramBuildInfo(program, device, CL.CL_PROGRAM_BUILD_LOG, buffer.length, Pointer.to(buffer), null);
            return new String(buffer, 0, buffer.length - 1, StandardCharsets.UTF_8).trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String getDeviceString(cl_device_id device, int paramName) {
        long[] size = new long[1];
        CL.clGetDeviceInfo(device, paramName, 0, null, size);
        byte[] buffer = new byte[(int) size[0]];
        CL.clGetDeviceInfo(device, paramName, buffer.length, Pointer.to(buffer), null);
        int len = buffer.length;
        while (len > 0 && buffer[len - 1] == 0) {
            len--;
        }
        return new String(buffer, 0, len, StandardCharsets.UTF_8).trim();
    }

    private static int getDeviceInt(cl_device_id device, int paramName) {
        int[] values = new int[1];
        CL.clGetDeviceInfo(device, paramName, Sizeof.cl_int, Pointer.to(values), null);
        return values[0];
    }

    private static String getDeviceTypeLabel(cl_device_id device) {
        long[] values = new long[1];
        CL.clGetDeviceInfo(device, CL.CL_DEVICE_TYPE, Sizeof.cl_ulong, Pointer.to(values), null);
        long type = values[0];
        if ((type & CL.CL_DEVICE_TYPE_GPU) != 0) {
            return "GPU";
        }
        if ((type & CL.CL_DEVICE_TYPE_CPU) != 0) {
            return "CPU";
        }
        if ((type & CL.CL_DEVICE_TYPE_ACCELERATOR) != 0) {
            return "ACCELERATOR";
        }
        return "DEFAULT";
    }

    private static void releaseMemQuietly(cl_mem mem) {
        if (mem != null) {
            try {
                CL.clReleaseMemObject(mem);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void releaseKernelQuietly(cl_kernel kernel) {
        if (kernel != null) {
            try {
                CL.clReleaseKernel(kernel);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void releaseProgramQuietly(cl_program program) {
        if (program != null) {
            try {
                CL.clReleaseProgram(program);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void releaseQueueQuietly(cl_command_queue queue) {
        if (queue != null) {
            try {
                CL.clReleaseCommandQueue(queue);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void releaseContextQuietly(cl_context context) {
        if (context != null) {
            try {
                CL.clReleaseContext(context);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
