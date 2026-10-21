// Root-only evidence driver for the verified 1080x2400 virtio touchscreen.
// This never ships in the Android application.
#include <linux/input.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
static int fd;
static void ev(unsigned short type,unsigned short code,int value) {
    struct input_event e={0};e.type=type;e.code=code;e.value=value;
    if(write(fd,&e,sizeof(e))!=sizeof(e)){perror("input event");exit(1);}
}
static void point(int slot,int x,int y){ev(EV_ABS,ABS_MT_SLOT,slot);ev(EV_ABS,ABS_MT_POSITION_X,x*32767/1080);ev(EV_ABS,ABS_MT_POSITION_Y,y*32767/2400);ev(EV_ABS,ABS_MT_TOOL_TYPE,MT_TOOL_FINGER);ev(EV_ABS,ABS_MT_PRESSURE,80);ev(EV_ABS,ABS_MT_TOUCH_MAJOR,8);}
int main(int argc,char **argv){
    if(argc!=3)return 2;
    int y=atoi(argv[1]),reverse=atoi(argv[2]);
    fd=open("/dev/input/event1",O_WRONLY);if(fd<0){perror("touchscreen");return 1;}
    for(int slot=0;slot<2;slot++){ev(EV_ABS,ABS_MT_SLOT,slot);ev(EV_ABS,ABS_MT_TRACKING_ID,101+slot);point(slot,slot?648:432,y);}ev(EV_SYN,SYN_REPORT,0);usleep(16000);
    for(int i=1;i<=30;i++){int d=216*i/30;if(reverse)d=-d;point(0,432-d,y);point(1,648+d,y);ev(EV_SYN,SYN_REPORT,0);usleep(16000);}
    for(int slot=0;slot<2;slot++){ev(EV_ABS,ABS_MT_SLOT,slot);ev(EV_ABS,ABS_MT_TRACKING_ID,-1);}ev(EV_SYN,SYN_REPORT,0);close(fd);return 0;
}
