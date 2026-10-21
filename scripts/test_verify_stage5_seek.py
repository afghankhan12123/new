#!/usr/bin/env python3
"""Host-only fail-closed tests for the production evidence verifier."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from PIL import Image
import verify_stage5_seek as v


class VerifierTests(unittest.TestCase):
    def test_clock_interval_includes_transport_and_quantization(self):
        s={'clock_calibration':dict(device_uptime_seconds='123456.78',host_before_ns=10,host_after_ns=30,precision_ns=10_000_000)}
        self.assertEqual(v.bounds(s),(123456780000000-30,123456790000000-10))

    def test_percentiles(self):
        self.assertEqual(v.statistics_for(list(range(1,21)))['p50'],10.5)
        self.assertEqual(v.statistics_for(list(range(1,21)))['p95'],19)
        self.assertIsNone(v.statistics_for([]))

    def test_equal_channel_sum_is_not_pixel_parity(self):
        result=v.score(Image.new('RGB',(64,64),'red'),Image.new('RGB',(64,64),'lime'))
        self.assertEqual(result['rgb_sum_mean'],0)
        with self.assertRaises(v.EvidenceError):v.strict_pixels({'frame':result})

    def test_marker_reads_real_pixels(self):
        image=Image.new('RGB',(1280,720),'black')
        for x in [22]+[58+36*k for k in range(8) if 149 & (1<<k)]:image.putpixel((x,22),(255,255,255))
        self.assertEqual(v.marker(image),149)
        image.putpixel((22,22),(127,127,127))
        with self.assertRaises(v.EvidenceError):v.marker(image)

    def fixture(self,root):
        s=dict(label='two-videos-warm-000',kind='two-videos',mode='warm',app_pid=7,
               request_host_ns=1_000_000_000,gesture_completed_host_ns=1_010_000_000,
               clock_calibration=dict(device_uptime_seconds='1',host_before_ns=0,host_after_ns=0,precision_ns=0),
               actual_requested_us=1_000_000,expected_frame_index=30,
               captures=[dict(file=f'{i}.png',start_host_ns=1_070_000_000+i*20_000_000,
                              end_host_ns=1_080_000_000+i*20_000_000) for i in range(3)])
        rows=[]
        def event(ms,name,value):rows.append(dict(ts=int(ms*1_000_000),name='Preview.'+name,value=value,pid=7,upid=8))
        event(1000,'player.9.graphIdentity',19);event(1001,'playerIdentity',9);event(1002,'rebuildCount',1)
        event(2000,'requestedTargetUs',1_000_000);event(2001,'acceptedTargetUs',1_000_000)
        event(2002,'dispatchedTargetUs',1_000_000);event(2003,'graphBuildTargetUs',1_000_000)
        event(2014,'player.10.graphIdentity',20);event(2015,'playerIdentity',10);event(2016,'rebuildCount',2)
        event(2017,'compositor.30.ownerGraphIdentity',20)
        for seq in (0,1):
            event(2018,f'player.10.sequence.{seq}.decoderInitializedBootMs',2018)
            event(2018.1,f'player.10.sequence.{seq}.decoderPreparationMs',5)
            event(2020,f'graph.20.input.{seq}.acceptedRendererPtsUs',1_000_000)
            event(2020.1,f'graph.20.input.{seq}.acceptedBufferPtsUs',1_000_001_000_000)
            event(2020.2,f'graph.20.input.{seq}.compositorIndex',1-seq)
        for seq in (1,0):
            event(2030+(1-seq),f'compositor.30.input.{seq}.selectedPtsUs',1_000_000)
            event(2030.1+(1-seq),'compositor.30.outputPtsUs',1_000_000)
        event(2050,'graph.20.outputReadyUs',1_000_000);event(2060,'graph.20.outputSubmittedUs',1_000_000)
        event(2001.1,'publishedPositionUs',1_000_000)
        (root/(s['label']+'-request-log.txt')).write_text('targetUs=1000000 player=9 generation=1')
        return s,sorted(rows,key=lambda r:r['ts'])

    def verify(self,mutate=None,expect_failure=False,expected_ms=60):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);s,rows=self.fixture(root)
            if mutate:mutate(s,rows)
            with patch.object(v,'inspect_capture',return_value=(Image.new('RGB',(10,10)),{'main':30,'right':30})),patch.object(v,'visual_scores',return_value={'scene':{'rgb_sum_mean':0,'channel_l1_mean':0}}):
                if expect_failure:
                    with self.assertRaises(v.EvidenceError):v.verify_sample(root,s,rows,root,root)
                else:
                    result=v.verify_sample(root,s,rows,root,root)
                    self.assertEqual(result['request_to_output_submitted_ms'],expected_ms)
                    self.assertEqual(result['graph_identity'],20)
                    return result

    def test_complete_association(self):self.verify()

    def test_wrong_process_cannot_supply_frame(self):
        self.verify(lambda s,rows:[r.update(upid=99) for r in rows if r['name']=='Preview.graph.20.outputSubmittedUs'],True)

    def test_latest_target_required(self):
        self.verify(lambda s,rows:rows.append(dict(ts=2_005_000_000,name='Preview.requestedTargetUs',value=2_000_000,pid=7,upid=8)),True)

    def test_partial_compositor_group_rejected(self):
        self.verify(lambda s,rows:rows.__setitem__(slice(None),[r for r in rows if r['name']!='Preview.compositor.30.input.0.selectedPtsUs']),True)

    def test_wrong_decoder_input_rejected(self):
        self.verify(lambda s,rows:[r.update(value=0) for r in rows if r['name']=='Preview.graph.20.input.0.compositorIndex'],True)

    def test_old_occurrence_of_same_frame_rejected(self):
        self.verify(lambda s,rows:[r.update(ts=1_500_000_000) for r in rows if r['name']=='Preview.graph.20.outputSubmittedUs'],True)

    def test_retired_player_rejected(self):
        self.verify(lambda s,rows:rows.append(dict(ts=2_069_000_000,name='Preview.playerIdentity',value=11,pid=7,upid=8)),True)

    def add_proven_recovery(self,s,rows):
        for capture in s['captures']:
            capture['start_host_ns']+=4_004_000_000;capture['end_host_ns']+=4_004_000_000
        for row in rows:
            if row['ts']>=2_014_000_000:
                row['ts']+=4_004_000_000
                if row['name']=='Preview.rebuildCount':row['value']=3
                if row['name'].endswith('.decoderInitializedBootMs'):row['value']+=4004
        def event(ms,name,value):rows.append(dict(ts=int(ms*1_000_000),name='Preview.'+name,value=value,pid=7,upid=8))
        event(2014,'player.11.graphIdentity',21);event(2015,'playerIdentity',11);event(2016,'rebuildCount',2)
        event(6016,'graphStartupRecovery',2);event(6016.1,'acceptedTargetUs',1_000_000)
        event(6016.2,'seekPlayerIdentity',11);event(6016.3,'dispatchedTargetUs',1_000_000)
        event(6016.4,'graphBuildTargetUs',1_000_000)
        rows.sort(key=lambda row:row['ts'])

    def recovery_mutation(self,name,value):
        def mutate(s,rows):
            self.add_proven_recovery(s,rows)
            for row in rows:
                if row['name']=='Preview.'+name:row['value']=value
        return mutate

    def test_proven_recovery_keeps_original_request_and_full_latency(self):
        result=self.verify(self.add_proven_recovery,expected_ms=4064)
        self.assertEqual(result['graph_generation'],3)
        self.assertEqual(result['graph_recovery']['failed_generation'],2)
        self.assertEqual(result['graph_recovery']['failed_graph_identity'],21)
        self.assertEqual(result['request_to_dispatch_ms'],2)

    def test_recovery_wrong_owner_rejected(self):
        self.verify(self.recovery_mutation('seekPlayerIdentity',99),True)

    def test_recovery_wrong_generation_rejected(self):
        self.verify(self.recovery_mutation('graphStartupRecovery',99),True)

    def test_recovery_missing_provenance_rejected(self):
        def mutate(s,rows):
            self.add_proven_recovery(s,rows)
            rows[:]=[r for r in rows if r['name']!='Preview.graphStartupRecovery']
        self.verify(mutate,True)

    def test_recovery_before_deadline_rejected(self):
        def mutate(s,rows):
            self.add_proven_recovery(s,rows)
            next(r for r in rows if r['name']=='Preview.graphStartupRecovery')['ts']=3_000_000_000
        self.verify(mutate,True)

    def test_second_recovery_player_rejected(self):
        def mutate(s,rows):
            self.add_proven_recovery(s,rows)
            rows.append(dict(ts=6_069_000_000,name='Preview.playerIdentity',value=12,pid=7,upid=8))
        self.verify(mutate,True)

    def test_recovery_cannot_replace_already_submitted_target(self):
        def mutate(s,rows):
            self.add_proven_recovery(s,rows)
            rows.append(dict(ts=3_000_000_000,name='Preview.graph.21.outputSubmittedUs',value=1_000_000,pid=7,upid=8))
        self.verify(mutate,True)

    def test_recovery_wrong_final_input_still_rejected(self):
        self.verify(self.recovery_mutation('graph.20.input.0.acceptedRendererPtsUs',0),True)

    def test_mismatched_recorded_marker_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);image=Image.new('RGB',(1280,720),'black');image.putpixel((22,22),(255,255,255));image.save(root/'frame.png')
            s=dict(kind='one-video',surface_bounds=[0,0,1280,720])
            with self.assertRaises(v.EvidenceError):v.inspect_capture(root,s,dict(file='frame.png',identities={'main':1}))


if __name__ == '__main__':unittest.main()
