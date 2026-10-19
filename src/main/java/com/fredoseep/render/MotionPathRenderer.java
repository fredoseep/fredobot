package com.fredoseep.render;

import com.fredoseep.motion.GroundRouteSearch;
import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionDebug;
import com.fredoseep.motion.MotionPlan;
import com.fredoseep.motion.MotionState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import java.util.List;

/** Tungsten-style layers (coarse route, tick boxes, execution and errors), rendered with 1.16 APIs.
 * New implementation: no dependency on the 26.2 gizmo API or changes to the legacy path renderer. */
public final class MotionPathRenderer {
    private static final int MAX_BOXES=500;
    private MotionPathRenderer(){}
    public static void render(MatrixStack matrices,Camera camera){
        MinecraftClient client=MinecraftClient.getInstance();
        if(client.world==null || client.player==null || !MotionDebug.visible(client.world))return;
        Vec3d eye=camera.getPos();Matrix4f matrix=matrices.peek().getModel();
        RenderSystem.pushMatrix();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        RenderSystem.disableTexture();RenderSystem.disableDepthTest();
        try {
            Tessellator tessellator=Tessellator.getInstance();BufferBuilder b=tessellator.getBuffer();
            b.begin(1,VertexFormats.POSITION_COLOR);
            LocalGroundSearch.Goal goal=MotionDebug.goal();
            if(goal!=null && near(eye,goal.x(),goal.y(),goal.z()))
                box(b,matrix,eye,goal.x()-.5,goal.y(),goal.z()-.5,1,2,1,.2f,1,.2f,.9f);
            List<GroundRouteSearch.Cell> coarse=MotionDebug.route();
            for(int i=0;i<Math.min(MAX_BOXES,coarse.size());i++){
                GroundRouteSearch.Cell p=coarse.get(i);
                if(!near(eye,p.x(),p.y(),p.z()))continue;
                box(b,matrix,eye,p.x()+.35,p.y()+.04,p.z()+.35,.3,.15,.3,.1f,.8f,1,.8f);
                if(i>0){GroundRouteSearch.Cell a=coarse.get(i-1);
                    line(b,matrix,eye,a.x()+.5,a.y()+.12,a.z()+.5,p.x()+.5,p.y()+.12,p.z()+.5,.1f,.8f,1,.8f);}
            }
            List<MotionPlan.Frame> frames=MotionDebug.frames();
            if(MotionDebug.searching())for(MotionState p:MotionDebug.explored()){
                if(near(eye,p.x(),p.y(),p.z()))box(b,matrix,eye,p.x()-.06,p.y()+.03,p.z()-.06,.12,.12,.12,.2f,.45f,1,.5f);
            }
            int stride=Math.max(1,(frames.size()+MAX_BOXES-1)/MAX_BOXES);
            for(int i=0;i<frames.size();i+=stride){
                MotionState p=frames.get(i).after();if(!near(eye,p.x(),p.y(),p.z()))continue;
                boolean completed=MotionDebug.executing() && i<MotionDebug.index();
                float r=completed?.4f:p.grounded()?.3f:1f,g=completed?.4f:p.grounded()?1f:.55f,blue=completed?.4f:.1f;
                if(MotionDebug.searching()){
                    r=.1f;g=1;blue=1;
                    box(b,matrix,eye,p.x()-.06,p.y()+.04,p.z()-.06,.12,.12,.12,r,g,blue,.75f);
                }else box(b,matrix,eye,p.x()-.3,p.y(),p.z()-.3,.6,1.8,.6,r,g,blue,.25f);
                if(i>0){MotionState a=frames.get(Math.max(0,i-stride)).after();
                    line(b,matrix,eye,a.x(),a.y()+.05,a.z(),p.x(),p.y()+.05,p.z(),r,g,blue,.9f);}
            }
            if(MotionDebug.executing() && MotionDebug.index()<frames.size()){
                MotionState p=frames.get(MotionDebug.index()).after();
                box(b,matrix,eye,p.x()-.31,p.y(),p.z()-.31,.62,1.82,.62,1,1,1,1);
            }
            MotionState expected=MotionDebug.expected(),actual=MotionDebug.actual();
            if(expected!=null && actual!=null){
                box(b,matrix,eye,expected.x()-.3,expected.y(),expected.z()-.3,.6,1.8,.6,1,.15f,.15f,1);
                box(b,matrix,eye,actual.x()-.31,actual.y(),actual.z()-.31,.62,1.82,.62,1,.1f,1,1);
                line(b,matrix,eye,actual.x(),actual.y()+.9,actual.z(),expected.x(),expected.y()+.9,expected.z(),1,.1f,.1f,1);
            }
            tessellator.draw();
        } finally {RenderSystem.enableDepthTest();RenderSystem.enableTexture();RenderSystem.disableBlend();RenderSystem.popMatrix();}
    }
    private static boolean near(Vec3d eye,double x,double y,double z){
        double dx=x-eye.x,dy=y-eye.y,dz=z-eye.z;return dx*dx+dy*dy+dz*dz<128*128;
    }
    private static void vertex(BufferBuilder b,Matrix4f m,Vec3d eye,double x,double y,double z,float r,float g,float blue,float alpha){
        b.vertex(m,(float)(x-eye.x),(float)(y-eye.y),(float)(z-eye.z)).color(r,g,blue,alpha).next();
    }
    private static void line(BufferBuilder b,Matrix4f m,Vec3d eye,double x,double y,double z,double xx,double yy,double zz,float r,float g,float blue,float alpha){
        vertex(b,m,eye,x,y,z,r,g,blue,alpha);vertex(b,m,eye,xx,yy,zz,r,g,blue,alpha);
    }
    private static void box(BufferBuilder b,Matrix4f m,Vec3d eye,double x,double y,double z,double w,double h,double d,float r,float g,float blue,float alpha){
        for(int a=0;a<2;a++)for(int c=0;c<2;c++){
            line(b,m,eye,x,y+a*h,z+c*d,x+w,y+a*h,z+c*d,r,g,blue,alpha);
            line(b,m,eye,x+a*w,y,z+c*d,x+a*w,y+h,z+c*d,r,g,blue,alpha);
            line(b,m,eye,x+a*w,y+c*h,z,x+a*w,y+c*h,z+d,r,g,blue,alpha);
        }
    }
}
