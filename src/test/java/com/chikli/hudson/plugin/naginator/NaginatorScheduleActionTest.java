/*
 * The MIT License
 * 
 * Copyright (c) 2015 IKEDA Yasuyuki
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.chikli.hudson.plugin.naginator;

import static org.junit.Assert.*;

import java.io.IOException;

import hudson.Launcher;
import hudson.matrix.Axis;
import hudson.matrix.AxisList;
import hudson.matrix.Combination;
import hudson.matrix.MatrixRun;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleProject;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 *
 */
public class NaginatorScheduleActionTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();
    
    public static class ScheduleActionBuilder extends Builder {
        private final NaginatorScheduleAction[] actions;
        
        public ScheduleActionBuilder(NaginatorScheduleAction... actions) {
            this.actions = actions;
        }
        
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            if (!(build instanceof MatrixRun)) {
                for(NaginatorScheduleAction action: actions) {
                    build.addAction(action);
                }
            } else {
                MatrixBuild parent = ((MatrixRun)build).getParentBuild();
                if(parent.getAction(NaginatorScheduleAction.class) == null) {
                    for(NaginatorScheduleAction action: actions) {
                        parent.addAction(action);
                    }
                }
            }
            return true;
        }
    }
    
    private static class AlwaysFalseScheduleAction extends NaginatorScheduleAction {
        public AlwaysFalseScheduleAction(int maxSchedule, ScheduleDelay delay, boolean rerunMatrixPart) {
            super(maxSchedule, delay, rerunMatrixPart);
        }
        
        @Override
        public boolean shouldSchedule(Run<?, ?> run, TaskListener listener, int retryCount) {
            return false;
        }
    }
    
    private static class MatrixConfigurationScheduleAction extends NaginatorScheduleAction {
        private final String combinationFilter;
        
        public MatrixConfigurationScheduleAction(String combinationFilter, int maxSchedule, ScheduleDelay delay, boolean rerunMatrixPart) {
            super(maxSchedule, delay, rerunMatrixPart);
            this.combinationFilter = combinationFilter;
        }
        
        @Override
        public boolean shouldScheduleForMatrixRun(MatrixRun run, TaskListener listener) {
            return run.getParent().getCombination().evalGroovyExpression(
                    run.getParent().getParent().getAxes(),
                    combinationFilter
            );
        }
    }
    
    @Test
    public void testShouldReschedule() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        FreeStyleBuild b = p.scheduleBuild2(0).get();
        
        {
            NaginatorScheduleAction target = new NaginatorScheduleAction(2);
            assertTrue(target.shouldSchedule(b, TaskListener.NULL, 0));
            assertTrue(target.shouldSchedule(b, TaskListener.NULL, 1));
            assertFalse(target.shouldSchedule(b, TaskListener.NULL, 2));
        }
        
        {
            NaginatorScheduleAction target = new NaginatorScheduleAction();
            assertEquals(0, target.getMaxSchedule());
            assertTrue(target.shouldSchedule(b, TaskListener.NULL, 0));
            assertTrue(target.shouldSchedule(b, TaskListener.NULL, 100));
            assertTrue(target.shouldSchedule(b, TaskListener.NULL, 2000));
        }
        
        {
            NaginatorScheduleAction target = new NaginatorScheduleAction(-1);
            assertTrue(target.shouldSchedule(b, TaskListener.NULL, 0));
            assertTrue(target.shouldSchedule(b, TaskListener.NULL, 100));
            assertTrue(target.shouldSchedule(b, TaskListener.NULL, 2000));
        }
    }
    
    @Test
    public void testNoNaginatorScheduleAction() throws Exception {
        // rescheduled specified times.
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new ScheduleActionBuilder());
        p.scheduleBuild2(0);
        j.waitUntilNoActivity();
        
        // no reschedule.
        assertEquals(1, p.getLastBuild().number);
    }
    
    @Test
    public void testFreeStyle() throws Exception {
        // rescheduled specified times.
        {
            FreeStyleProject p = j.createFreeStyleProject();
            p.getBuildersList().add(new ScheduleActionBuilder(
                    new NaginatorScheduleAction(
                            2,
                            new FixedDelay(0),
                            false
                    )
            ));
            p.scheduleBuild2(0);
            j.waitUntilNoActivity();
            
            // retries 2 times.
            assertEquals(3, p.getLastBuild().number);
        }
        
        // not scheduled when returning false.
        {
            FreeStyleProject p = j.createFreeStyleProject();
            p.getBuildersList().add(new ScheduleActionBuilder(
                    new AlwaysFalseScheduleAction(
                            2,
                            new FixedDelay(0),
                            false
                    )
            ));
            p.scheduleBuild2(0);
            j.waitUntilNoActivity();
            
            // retries 0 times.
            assertEquals(1, p.getLastBuild().number);
        }
        
        // scheduled when any of actions returns true.
        {
            FreeStyleProject p = j.createFreeStyleProject();
            p.getBuildersList().add(new ScheduleActionBuilder(
                    new AlwaysFalseScheduleAction(
                            2,
                            new FixedDelay(0),
                            false
                    ),
                    new NaginatorScheduleAction(
                            2,
                            new FixedDelay(0),
                            false
                    )
            ));
            p.scheduleBuild2(0);
            j.waitUntilNoActivity();
            
            // retries 2 times.
            assertEquals(3, p.getLastBuild().number);
        }
    }
    
    @Test
    public void testMatrix() throws Exception {
        // all children are rescheduled.
        {
            MatrixProject p = j.createMatrixProject();
            AxisList axes = new AxisList(
                    new Axis("axis1", "1", "2"),
                    new Axis("axis2", "1", "2")
            );
            p.setAxes(axes);
            p.setCombinationFilter("!(axis1=='2' && axis2=='2')");
            p.getBuildersList().add(new ScheduleActionBuilder(
                    new NaginatorScheduleAction(
                            2,
                            new FixedDelay(0),
                            true
                    )
            ));
            p.scheduleBuild2(0);
            j.waitUntilNoActivity();
            
            // retries 2 times.
            assertEquals(3, p.getLastBuild().number);
            
            // (1, 1), (1, 2), (2, 1) are scheduled
            MatrixBuild b = p.getLastBuild();
            assertNotNull(b.getExactRun(new Combination(axes, "1", "1")));
            assertNotNull(b.getExactRun(new Combination(axes, "1", "2")));
            assertNotNull(b.getExactRun(new Combination(axes, "2", "1")));
            assertNull(b.getExactRun(new Combination(axes, "2", "2")));
        }
        
        // specified children are rescheduled.
        {
            MatrixProject p = j.createMatrixProject();
            AxisList axes = new AxisList(
                    new Axis("axis1", "1", "2"),
                    new Axis("axis2", "1", "2")
            );
            p.setAxes(axes);
            p.setCombinationFilter("!(axis1=='2' && axis2=='2')");
            p.getBuildersList().add(new ScheduleActionBuilder(
                    new MatrixConfigurationScheduleAction(
                            "(axis1=='1' && axis2=='2') || (axis1=='2' && axis2=='1')",
                            1,
                            new FixedDelay(0),
                            true
                    )
            ));
            p.scheduleBuild2(0);
            j.waitUntilNoActivity();
            
            // retries 1 times.
            assertEquals(2, p.getLastBuild().number);
            
            // (1, 2), (2, 1) are scheduled
            MatrixBuild b = p.getLastBuild();
            assertNull(b.getExactRun(new Combination(axes, "1", "1")));
            assertNotNull(b.getExactRun(new Combination(axes, "1", "2")));
            assertNotNull(b.getExactRun(new Combination(axes, "2", "1")));
            assertNull(b.getExactRun(new Combination(axes, "2", "2")));
        }
        
        // all children are rescheduled if no children is specified to reschedule.
        {
            MatrixProject p = j.createMatrixProject();
            AxisList axes = new AxisList(
                    new Axis("axis1", "1", "2"),
                    new Axis("axis2", "1", "2")
            );
            p.setAxes(axes);
            p.setCombinationFilter("!(axis1=='2' && axis2=='2')");
            p.getBuildersList().add(new ScheduleActionBuilder(
                    new MatrixConfigurationScheduleAction(
                            "false",
                            1,
                            new FixedDelay(0),
                            true
                    )
            ));
            p.scheduleBuild2(0);
            j.waitUntilNoActivity();
            
            // retries 1 times.
            assertEquals(2, p.getLastBuild().number);
            
            // (1, 1), (1, 2), (2, 1) are scheduled
            MatrixBuild b = p.getLastBuild();
            assertNotNull(b.getExactRun(new Combination(axes, "1", "1")));
            assertNotNull(b.getExactRun(new Combination(axes, "1", "2")));
            assertNotNull(b.getExactRun(new Combination(axes, "2", "1")));
            assertNull(b.getExactRun(new Combination(axes, "2", "2")));
        }
        
        // all children are rescheduled if rerunMatrixPart is set to false.
        {
            MatrixProject p = j.createMatrixProject();
            AxisList axes = new AxisList(
                    new Axis("axis1", "1", "2"),
                    new Axis("axis2", "1", "2")
            );
            p.setAxes(axes);
            p.setCombinationFilter("!(axis1=='2' && axis2=='2')");
            p.getBuildersList().add(new ScheduleActionBuilder(
                    new MatrixConfigurationScheduleAction(
                            "(axis1=='1' && axis2=='2') || (axis1=='2' && axis2=='1')",
                            1,
                            new FixedDelay(0),
                            false
                    )
            ));
            p.scheduleBuild2(0);
            j.waitUntilNoActivity();
            
            // retries 1 times.
            assertEquals(2, p.getLastBuild().number);
            
            // (1, 1), (1, 2), (2, 1) are scheduled
            MatrixBuild b = p.getLastBuild();
            assertNotNull(b.getExactRun(new Combination(axes, "1", "1")));
            assertNotNull(b.getExactRun(new Combination(axes, "1", "2")));
            assertNotNull(b.getExactRun(new Combination(axes, "2", "1")));
            assertNull(b.getExactRun(new Combination(axes, "2", "2")));
        }
    }
}
