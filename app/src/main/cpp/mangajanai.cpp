#include <jni.h>
#include <net.h>
#include <gpu.h>
#include <pipelinecache.h>
#include <memory>
#include <mutex>
#include <cstring>
#include <stdexcept>
#include <cstdio>
#include <string>

namespace {
std::once_flag gpuOnce;
void fail(JNIEnv* env, const char* text) {
    env->ThrowNew(env->FindClass("java/io/IOException"), text);
}
std::string string(JNIEnv* env, jstring value) {
    const char* chars=env->GetStringUTFChars(value,nullptr);
    if (!chars) throw std::runtime_error("Cannot read path");
    std::string result(chars); env->ReleaseStringUTFChars(value,chars); return result;
}
struct Model {
    ncnn::Net net;
    std::unique_ptr<ncnn::PipelineCache> pipeline;
    const ncnn::VulkanDevice* device=nullptr;
    ncnn::VkAllocator* blobs=nullptr;
    ncnn::VkAllocator* staging=nullptr;
    ncnn::Mat packed;
    std::string cache;
    Model(const std::string& param, const std::string& weights, const std::string& path) : cache(path) {
        std::call_once(gpuOnce,[]{ncnn::create_gpu_instance();});
        if (ncnn::get_gpu_count()<1) throw std::runtime_error("Vulkan unavailable");
        device=ncnn::get_gpu_device(ncnn::get_default_gpu_index());
        if (!device) throw std::runtime_error("Vulkan device unavailable");
        net.set_vulkan_device(device);
        net.opt.use_vulkan_compute=true;
        net.opt.num_threads=4;
        net.opt.lightmode=true;
        net.opt.use_fp16_packed=false;
        net.opt.use_fp16_storage=false;
        net.opt.use_fp16_arithmetic=false;
        net.opt.use_fp16_uniform=false;
        net.opt.use_bf16_storage=false;
        net.opt.use_bf16_packed=false;
        net.opt.use_int8_inference=false;
        net.opt.use_int8_packed=false;
        net.opt.use_int8_storage=false;
        net.opt.use_int8_arithmetic=false;
        net.opt.use_int8_uniform=false;
        net.opt.use_cooperative_matrix=false;
        net.opt.use_mapped_model_loading=true;
        net.opt.use_weights_in_host_memory=false;
        net.opt.openmp_blocktime=0;
        pipeline=std::make_unique<ncnn::PipelineCache>(device);
        net.opt.pipeline_cache=pipeline.get();
        FILE* existing=fopen(cache.c_str(),"rb");
        if (existing) {
            fseek(existing,0,SEEK_END); long size=ftell(existing); rewind(existing);
            if (size>0 && size<16*1024*1024) pipeline->load_cache(existing);
            fclose(existing);
        }
        if (net.load_param(param.c_str())!=0 || net.load_model(weights.c_str())!=0) {
            net.clear(); // pipeline must stay alive while layers are destroyed
            throw std::runtime_error("Cannot load MangaJaNai FP32 model");
        }
        blobs=device->acquire_blob_allocator(); staging=device->acquire_staging_allocator();
        net.opt.blob_vkallocator=blobs; net.opt.workspace_vkallocator=blobs;
        net.opt.staging_vkallocator=staging;
    }
    ~Model() {
        net.clear();
        // Returning an allocator alone keeps its cached Vulkan buffers alive globally.
        // Clear after all tensors/layers are freed, so idle/trim really releases GPU memory.
        if (blobs) { blobs->clear(); device->reclaim_blob_allocator(blobs); }
        if (staging) { staging->clear(); device->reclaim_staging_allocator(staging); }
    }
    void run(const float* input, float* output, int side) {
        const int half=side/2, area=side*side;
        packed.create(half,half,12);
        if (packed.empty()) throw std::runtime_error("Cannot allocate input");
        // Exactly the original ONNX pixel-unshuffle: [C,dy,dx,H/2,W/2].
        // Only the layout changes; no resizing, rounding or weight compression.
        for (int c=0;c<3;c++) for (int dy=0;dy<2;dy++) for (int dx=0;dx<2;dx++) {
            float* channel=packed.channel(c*4+dy*2+dx);
            for (int y=0;y<half;y++) for (int x=0;x<half;x++)
                channel[y*half+x]=input[c*area+(y*2+dy)*side+x*2+dx];
        }
        ncnn::Extractor extractor=net.create_extractor();
        ncnn::Mat result;
        if (extractor.input("3",packed)!=0 || extractor.extract("out0",result)!=0)
            throw std::runtime_error("MangaJaNai GPU inference failed");
        if (result.w!=side*2 || result.h!=side*2 || result.c!=3 || result.elempack!=1 || result.elemsize!=4)
            throw std::runtime_error("Unexpected MangaJaNai output");
        const int outArea=area*4;
        for (int c=0;c<3;c++) memcpy(output+c*outArea,result.channel(c),outArea*sizeof(float));
    }
    void save() {
        const auto temporary=cache+".part";
        if (pipeline->save_cache(temporary.c_str())==0) rename(temporary.c_str(),cache.c_str());
        else remove(temporary.c_str());
    }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_com_example_ui_comic_MangaJaNaiGpu_create(JNIEnv* env,jobject,jstring param,jstring weights,jstring cache) {
    try { return reinterpret_cast<jlong>(new Model(string(env,param),string(env,weights),string(env,cache))); }
    catch (const std::exception& e) { fail(env,e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_example_ui_comic_MangaJaNaiGpu_run(JNIEnv* env,jobject,jlong handle,jobject input,jobject output,jint side) {
    try {
        if (!handle || side<80 || side>272 || side%2 || env->GetDirectBufferCapacity(input)<3LL*side*side*4 || env->GetDirectBufferCapacity(output)<12LL*side*side*4)
            throw std::runtime_error("Invalid MangaJaNai buffers");
        auto* source=static_cast<float*>(env->GetDirectBufferAddress(input));
        auto* target=static_cast<float*>(env->GetDirectBufferAddress(output));
        if (!source || !target) throw std::runtime_error("Direct buffers required");
        reinterpret_cast<Model*>(handle)->run(source,target,side);
    } catch (const std::exception& e) { fail(env,e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_com_example_ui_comic_MangaJaNaiGpu_save(JNIEnv* env,jobject,jlong handle) {
    if (handle) reinterpret_cast<Model*>(handle)->save();
}
extern "C" JNIEXPORT void JNICALL Java_com_example_ui_comic_MangaJaNaiGpu_destroy(JNIEnv*,jobject,jlong handle) {
    delete reinterpret_cast<Model*>(handle);
}
